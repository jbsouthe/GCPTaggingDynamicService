package com.singularity.ee.service.tagging.model;

public class BatchResponse {
    public String entityType;
    public BatchStatus success;
    public BatchStatus failure;

    public String toString() {
        return entityType +" Success: "+ success +" Failure: "+ failure;
    }
}
