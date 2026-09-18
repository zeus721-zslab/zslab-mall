package com.zslab.mall.stats.repository;

import com.zslab.mall.auth.enums.RoleCode;
import com.zslab.mall.order.entity.Order;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 관리자 회원 통계 전용 집계 리포지토리(Track 88·D-182). CRUD를 노출하지 않는 {@link Repository} 마커만 상속하며 JPQL은 User·UserRole·
 * BuyerProfile·BuyerGrade·Order를 대상으로 한다. BUYER 판정은 {@code AdminMemberSpecifications.buyerRole}·대시보드와 같은 user_role 서브쿼리.
 * User는 @SQLRestriction(deleted_at IS NULL)이라 soft-delete 회원은 모든 집계에서 빠진다(관리자 회원 목록과 동일).
 *
 * <p>등급은 buyer_profile.grade_id(현재 등급) 경유다 — 주문 시점 등급 스냅샷이 없어 등급 재산정 시 과거 매출의 등급 귀속이 바뀐다(D-182).
 * 네이티브 쿼리를 쓰지 않으며 모든 변수는 :바인딩만 사용해 SQL injection 위험이 없다.
 */
public interface AdminMemberStatsRepository extends Repository<Order, Long> {

    /** 기간 신규 가입 BUYER 수(created_at·탈퇴자 포함·D-180). */
    @Query("SELECT COUNT(u) FROM User u WHERE u.createdAt >= :from AND u.createdAt < :to "
            + "AND u.id IN (SELECT ur.userId FROM UserRole ur WHERE ur.role.code = :role)")
    long countSignups(@Param("role") RoleCode role, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 기간 탈퇴 BUYER 수(withdrawn_at). */
    @Query("SELECT COUNT(u) FROM User u WHERE u.withdrawnAt >= :from AND u.withdrawnAt < :to "
            + "AND u.id IN (SELECT ur.userId FROM UserRole ur WHERE ur.role.code = :role)")
    long countWithdrawals(@Param("role") RoleCode role, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 기준 시각 이전 가입 BUYER 누계(활성 누적 기준값·탈퇴자 포함). */
    @Query("SELECT COUNT(u) FROM User u WHERE u.createdAt < :before "
            + "AND u.id IN (SELECT ur.userId FROM UserRole ur WHERE ur.role.code = :role)")
    long countSignupsBefore(@Param("role") RoleCode role, @Param("before") LocalDateTime before);

    /** 기준 시각 이전 탈퇴 BUYER 누계(활성 누적 기준값). */
    @Query("SELECT COUNT(u) FROM User u WHERE u.withdrawnAt < :before "
            + "AND u.id IN (SELECT ur.userId FROM UserRole ur WHERE ur.role.code = :role)")
    long countWithdrawalsBefore(@Param("role") RoleCode role, @Param("before") LocalDateTime before);

    /** 구간별 신규 가입 BUYER 수. pattern은 {@code StatsBuckets} 상수만 전달한다. */
    @Query("SELECT FUNCTION('DATE_FORMAT', u.createdAt, :pattern) AS bucket, COUNT(u) AS bucketCount FROM User u "
            + "WHERE u.createdAt >= :from AND u.createdAt < :to "
            + "AND u.id IN (SELECT ur.userId FROM UserRole ur WHERE ur.role.code = :role) "
            + "GROUP BY FUNCTION('DATE_FORMAT', u.createdAt, :pattern)")
    List<StatsCountBucketProjection> countSignupsByBucket(@Param("pattern") String pattern, @Param("role") RoleCode role,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 구간별 탈퇴 BUYER 수(withdrawn_at). */
    @Query("SELECT FUNCTION('DATE_FORMAT', u.withdrawnAt, :pattern) AS bucket, COUNT(u) AS bucketCount FROM User u "
            + "WHERE u.withdrawnAt >= :from AND u.withdrawnAt < :to "
            + "AND u.id IN (SELECT ur.userId FROM UserRole ur WHERE ur.role.code = :role) "
            + "GROUP BY FUNCTION('DATE_FORMAT', u.withdrawnAt, :pattern)")
    List<StatsCountBucketProjection> countWithdrawalsByBucket(@Param("pattern") String pattern, @Param("role") RoleCode role,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 현재 등급별 활성(미탈퇴) BUYER 수(buyer_profile.grade_id 경유·0건 등급은 행 없음 → 서비스가 3등급으로 채움). */
    @Query("SELECT g.code AS gradeCode, COUNT(bp) AS memberCount FROM BuyerProfile bp JOIN bp.user u, BuyerGrade g "
            + "WHERE bp.gradeId = g.id AND u.withdrawnAt IS NULL GROUP BY g.code")
    List<GradeCountProjection> countActiveMembersByGrade();

    /** 구매자의 현재 등급별 기간 결제 매출 합(order.total_price·paid_at). */
    @Query("SELECT g.code AS gradeCode, COALESCE(SUM(o.totalPrice), 0) AS revenue FROM Order o, BuyerProfile bp, BuyerGrade g "
            + "WHERE o.buyerId = bp.userId AND bp.gradeId = g.id AND o.paidAt >= :from AND o.paidAt < :to GROUP BY g.code")
    List<GradeRevenueProjection> sumRevenueByGrade(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 구매자별 기간 결제 주문 건수·매출 합(매출 내림차순·동률 buyer id 오름차순). 재구매·분리·상위 회원은 서비스가 이 결과에서 산출한다. */
    @Query("SELECT o.buyerId AS buyerId, COUNT(o) AS orderCount, COALESCE(SUM(o.totalPrice), 0) AS revenue FROM Order o "
            + "WHERE o.paidAt >= :from AND o.paidAt < :to GROUP BY o.buyerId ORDER BY SUM(o.totalPrice) DESC, o.buyerId ASC")
    List<BuyerOrdersProjection> aggregateOrdersByBuyer(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
