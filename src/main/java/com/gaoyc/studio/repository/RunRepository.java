package com.gaoyc.studio.repository;

import java.util.List;
import java.util.Optional;

import com.gaoyc.studio.domain.RunRecord;

/** 生成历史仓库。默认实现是 JSON 行文件，换成 JDBC 只需另写一个实现。 */
public interface RunRepository {

    void save(RunRecord record);

    Optional<RunRecord> find(String runId);

    /** 最近的记录，按时间倒序。 */
    List<RunRecord> recent(int limit);

    long count();
}
