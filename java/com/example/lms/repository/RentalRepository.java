package com.example.lms.repository;

import com.example.lms.domain.Rental;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;



@Repository
public interface RentalRepository extends JpaRepository<Rental, Long> {
    // 필요에 따라 여기에 커스텀 쿼리 메소드를 추가할 수 있습니다.
    // 예: List<Rental> findByRenterName(String renterName);
}