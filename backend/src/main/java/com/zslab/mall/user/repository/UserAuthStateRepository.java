package com.zslab.mall.user.repository;

import com.zslab.mall.user.entity.UserAuthState;
import org.springframework.data.jpa.repository.JpaRepository;

/** 인증 필터용 회원 상태 조회(Track 84). soft-delete 회원도 반환한다({@code @SQLRestriction} 없는 읽기 모델). */
public interface UserAuthStateRepository extends JpaRepository<UserAuthState, Long> {
}
