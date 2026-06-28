package com.zslab.mall.attachment.repository;

import com.zslab.mall.attachment.entity.Attachment;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    Optional<Attachment> findByPublicId(String publicId);

    List<Attachment> findByTargetTypeAndTargetId(PolymorphicTargetType targetType, Long targetId);
}
