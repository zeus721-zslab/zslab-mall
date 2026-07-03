package com.zslab.mall.user.repository;

import com.zslab.mall.user.entity.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    /** 로그인 이메일로 회원 조회. {@code @SQLRestriction("deleted_at IS NULL")}로 소프트삭제 회원은 제외된다. (Track 33) */
    Optional<User> findByEmail(String email);

    /** 이메일 중복 여부. 회원가입 사전 검증용. {@code @SQLRestriction}으로 소프트삭제 회원은 제외된다. (Track 34) */
    boolean existsByEmail(String email);
}
