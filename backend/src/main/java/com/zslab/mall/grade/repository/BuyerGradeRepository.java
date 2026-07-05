package com.zslab.mall.grade.repository;

import com.zslab.mall.grade.entity.BuyerGrade;
import com.zslab.mall.grade.enums.BuyerGradeCode;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BuyerGradeRepository extends JpaRepository<BuyerGrade, Long> {

    Optional<BuyerGrade> findByCode(BuyerGradeCode code);
}
