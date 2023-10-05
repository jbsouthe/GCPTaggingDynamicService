package com.singularity.ee.service.tagging.model;

import java.util.List;

public class BatchStatus {
    public long count;
    public List<Long> entityIds;

    public String toString() {
        return String.format("%d: %s",count,entityIds);
    }
}
