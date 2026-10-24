package com.flashgrid.controller;

import com.flashgrid.model.FlashItem;
import com.flashgrid.model.Order;
import com.flashgrid.service.FlashSaleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/flash-sales")
@RequiredArgsConstructor
public class FlashSaleController {

    private final FlashSaleService flashSaleService;

    /** Creates a flash item in PostgreSQL and loads its stock into Redis. */
    @PostMapping
    public ResponseEntity<FlashSaleResponse> createFlashSale(@Valid @RequestBody CreateFlashSaleRequest request) {
        FlashItem item = flashSaleService.createFlashSale(
                request.title(),
                request.stock(),
                request.price(),
                request.startTime(),
                request.endTime());
        return ResponseEntity.status(HttpStatus.CREATED).body(FlashSaleResponse.from(item));
    }

    /** Reloads an existing item's stock/time window from PostgreSQL into Redis. */
    @PostMapping("/{itemId}/init")
    public ResponseEntity<Map<String, Object>> reinitialize(@PathVariable Long itemId) {
        flashSaleService.warmUp(itemId);
        return ResponseEntity.ok(Map.of("itemId", itemId, "message", "Flash sale loaded into Redis"));
    }

    /** Main high-traffic endpoint: a user tries to buy one unit of the item. */
    @PostMapping("/{itemId}/purchase")
    public ResponseEntity<PurchaseResponse> purchase(@PathVariable Long itemId,
                                                     @Valid @RequestBody PurchaseRequest request) {
        Order order = flashSaleService.purchase(request.userId(), itemId);
        return ResponseEntity.status(HttpStatus.CREATED).body(PurchaseResponse.from(order));
    }

    /** Remaining stock as seen by Redis. */
    @GetMapping("/{itemId}/stock")
    public ResponseEntity<Map<String, Object>> stock(@PathVariable Long itemId) {
        long available = flashSaleService.getAvailableStock(itemId);
        return ResponseEntity.ok(Map.of("itemId", itemId, "availableStock", available));
    }

    // ------------------------------------------------------------------
    // Request / response payloads
    // ------------------------------------------------------------------

    public record CreateFlashSaleRequest(
            @NotBlank String title,
            @NotNull @Min(1) Integer stock,
            @NotNull @DecimalMin("0.01") BigDecimal price,
            @NotNull LocalDateTime startTime,
            @NotNull LocalDateTime endTime) {
    }

    public record PurchaseRequest(@NotNull Long userId) {
    }

    public record FlashSaleResponse(
            Long id,
            String title,
            Integer stock,
            BigDecimal price,
            LocalDateTime startTime,
            LocalDateTime endTime) {

        static FlashSaleResponse from(FlashItem item) {
            return new FlashSaleResponse(
                    item.getId(),
                    item.getTitle(),
                    item.getStock(),
                    item.getPrice(),
                    item.getStartTime(),
                    item.getEndTime());
        }
    }

    public record PurchaseResponse(
            Long orderId,
            Long userId,
            Long itemId,
            BigDecimal price,
            String status,
            LocalDateTime createdAt) {

        static PurchaseResponse from(Order order) {
            return new PurchaseResponse(
                    order.getId(),
                    order.getUserId(),
                    order.getItemId(),
                    order.getPrice(),
                    order.getStatus().name(),
                    order.getCreatedAt());
        }
    }
}