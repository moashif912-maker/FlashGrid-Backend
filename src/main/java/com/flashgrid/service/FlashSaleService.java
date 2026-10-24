package com.flashgrid.service;

import com.flashgrid.model.FlashItem;
import com.flashgrid.model.Order;
import com.flashgrid.model.OrderStatus;
import com.flashgrid.repository.FlashItemRepository;
import com.flashgrid.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Core flash-sale logic.
 *
 * Flow of a purchase:
 *   1. Check the sale window (cached in Redis, no DB hit).
 *   2. Run the Lua script: stock check + duplicate-buyer check + decrement, atomically.
 *   3. If Redis approved, persist the order and decrement DB stock in one transaction.
 *   4. If the DB step fails, give the stock back in Redis (compensation).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlashSaleService {

    private static final String STOCK_KEY = "flash:stock:";
    private static final String BUYERS_KEY = "flash:buyers:";
    private static final String META_KEY = "flash:meta:";
    private static final String INIT_LOCK_KEY = "flash:lock:init:";
    private static final Duration KEY_TTL_BUFFER = Duration.ofHours(1);

    private final StringRedisTemplate redis;
    private final RedisScript<Long> flashPurchaseScript;
    private final RedissonClient redissonClient;
    private final FlashItemRepository flashItemRepository;
    private final OrderRepository orderRepository;
    private final TransactionTemplate transactionTemplate;
    private final RateLimiterService rateLimiterService;

    // ------------------------------------------------------------------
    // Sale setup
    // ------------------------------------------------------------------

    /** Saves a new flash item in PostgreSQL and loads its stock into Redis. */
    public FlashItem createFlashSale(String title, int stock, BigDecimal price,
                                     LocalDateTime startTime, LocalDateTime endTime) {
        if (stock <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stock must be greater than 0");
        }
        if (price == null || price.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Price must be greater than 0");
        }
        if (!endTime.isAfter(startTime)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "End time must be after start time");
        }
        if (!endTime.isAfter(LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "End time must be in the future");
        }

        FlashItem item = flashItemRepository.save(FlashItem.builder()
                .title(title)
                .stock(stock)
                .price(price)
                .startTime(startTime)
                .endTime(endTime)
                .build());

        warmUp(item);
        return item;
    }

    /**
     * Loads (or reloads) an item's stock, price and time window into Redis.
     * A Redisson distributed lock stops two requests from initializing the same sale at once.
     */
    public void warmUp(FlashItem item) {
        RLock lock = redissonClient.getLock(INIT_LOCK_KEY + item.getId());
        boolean locked = false;
        try {
            locked = lock.tryLock(3, 10, TimeUnit.SECONDS);
            if (!locked) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Sale is already being initialized");
            }

            Duration ttl = Duration.between(LocalDateTime.now(), item.getEndTime()).plus(KEY_TTL_BUFFER);
            if (ttl.isNegative() || ttl.isZero()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Sale has already ended");
            }

            String stockKey = STOCK_KEY + item.getId();
            String buyersKey = BUYERS_KEY + item.getId();
            String metaKey = META_KEY + item.getId();

            Map<String, String> meta = new HashMap<>();
            meta.put("start", String.valueOf(toEpochMillis(item.getStartTime())));
            meta.put("end", String.valueOf(toEpochMillis(item.getEndTime())));
            meta.put("price", item.getPrice().toPlainString());

            redis.delete(buyersKey);
            redis.opsForValue().set(stockKey, String.valueOf(item.getStock()), ttl);
            redis.opsForHash().putAll(metaKey, meta);
            redis.expire(metaKey, ttl);

            log.info("Flash sale {} loaded into Redis with stock {}", item.getId(), item.getStock());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Initialization interrupted");
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /** Re-initializes an existing item's sale in Redis using the values stored in PostgreSQL. */
    public void warmUp(Long itemId) {
        FlashItem item = flashItemRepository.findById(itemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Flash item not found"));
        warmUp(item);
    }

    // ------------------------------------------------------------------
    // Purchase
    // ------------------------------------------------------------------

    public Order purchase(Long userId, Long itemId) {
        rateLimiterService.checkUser(userId);
        BigDecimal price = checkSaleWindow(itemId);

        Long result = redis.execute(
                flashPurchaseScript,
                List.of(STOCK_KEY + itemId, BUYERS_KEY + itemId),
                String.valueOf(userId));

        if (result == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Redis returned no result");
        }

        return switch (result.intValue()) {
            case 1 -> persistOrder(userId, itemId, price);
            case 0 -> throw new ResponseStatusException(HttpStatus.GONE, "Sold out");
            case -1 -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Flash sale not initialized");
            case -2 -> throw new ResponseStatusException(HttpStatus.CONFLICT, "You have already purchased this item");
            default -> throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected result: " + result);
        };
    }

    /** Remaining stock as currently seen by Redis. */
    public long getAvailableStock(Long itemId) {
        String value = redis.opsForValue().get(STOCK_KEY + itemId);
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Flash sale not initialized");
        }
        return Long.parseLong(value);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** Validates the sale window using the data cached in Redis and returns the sale price. */
    private BigDecimal checkSaleWindow(Long itemId) {
        List<Object> meta = redis.opsForHash().multiGet(META_KEY + itemId, List.of("start", "end", "price"));

        if (meta == null || meta.get(0) == null || meta.get(1) == null || meta.get(2) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Flash sale not initialized");
        }

        long start = Long.parseLong(meta.get(0).toString());
        long end = Long.parseLong(meta.get(1).toString());
        long now = System.currentTimeMillis();

        if (now < start) {
            throw new ResponseStatusException(HttpStatus.TOO_EARLY, "Sale has not started yet");
        }
        if (now > end) {
            throw new ResponseStatusException(HttpStatus.GONE, "Sale has ended");
        }
        return new BigDecimal(meta.get(2).toString());
    }

    /** Writes the order and DB stock in one transaction; undoes the Redis decrement on failure. */
    private Order persistOrder(Long userId, Long itemId, BigDecimal price) {
        try {
            return transactionTemplate.execute(status -> {
                int updated = flashItemRepository.decrementStock(itemId);
                if (updated == 0) {
                    throw new IllegalStateException("Database stock exhausted for item " + itemId);
                }
                return orderRepository.save(Order.builder()
                        .userId(userId)
                        .itemId(itemId)
                        .price(price)
                        .status(OrderStatus.CREATED)
                        .build());
            });
        } catch (DataIntegrityViolationException e) {
            // The database already has an order for this user + item (unique constraint).
            releaseStock(userId, itemId, false);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You have already purchased this item");
        } catch (RuntimeException e) {
            log.error("Order persistence failed for user {} item {}", userId, itemId, e);
            releaseStock(userId, itemId, true);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Purchase failed, please try again");
        }
    }

    /** Compensation: returns the reserved unit to Redis (and optionally frees the buyer slot). */
    private void releaseStock(Long userId, Long itemId, boolean removeBuyer) {
        redis.opsForValue().increment(STOCK_KEY + itemId);
        if (removeBuyer) {
            redis.opsForSet().remove(BUYERS_KEY + itemId, String.valueOf(userId));
        }
    }

    private long toEpochMillis(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}