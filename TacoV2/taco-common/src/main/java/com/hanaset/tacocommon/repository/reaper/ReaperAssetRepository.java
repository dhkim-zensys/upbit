package com.hanaset.tacocommon.repository.reaper;

import com.hanaset.tacocommon.entity.reaper.ReaperAssetEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ReaperAssetRepository extends JpaRepository<ReaperAssetEntity, Long> {

    Optional<ReaperAssetEntity> findByAsset(String pair);
}
