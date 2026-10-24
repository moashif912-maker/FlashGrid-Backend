package com.flashgrid.repository;

import com.flashgrid.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    /** True if this user already bought this item (one purchase per user per item). */
    boolean existsByUserIdAndItemId(Long userId, Long itemId);

    List<Order> findByUserId(Long userId);

    List<Order> findByItemId(Long itemId);

    long countByItemId(Long itemId);
}