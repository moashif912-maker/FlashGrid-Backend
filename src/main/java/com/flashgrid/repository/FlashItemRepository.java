package com.flashgrid.repository;

import com.flashgrid.model.FlashItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface FlashItemRepository extends JpaRepository<FlashItem, Long> {

    /** Items whose sale window is currently open. */
    List<FlashItem> findByStartTimeBeforeAndEndTimeAfter(LocalDateTime now1, LocalDateTime now2);

    /**
     * Atomic DB-side stock decrement used to sync Redis sales into PostgreSQL.
     * The "stock > 0" guard means the row can never go negative.
     * Returns 1 if stock was decremented, 0 if the item was sold out or missing.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE FlashItem f
               SET f.stock = f.stock - 1,
                   f.version = f.version + 1
             WHERE f.id = :id
               AND f.stock > 0
            """)
    int decrementStock(@Param("id") Long id);
}