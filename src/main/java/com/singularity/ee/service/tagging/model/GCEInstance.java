package com.singularity.ee.service.tagging.model;
import java.util.List;
import java.util.Map;

public class GCEInstance {
    public String id;
    public String creationTimestamp;
    public String name;
    public String description;
    public String zone;
    public String machineType;
    public String status;
    public String statusMessage;
    public Boolean canIpForward;
    public List<NetworkInterface> networkInterfaces;
    public List<Disk> disks;
    public Metadata metadata;
    public List<ServiceAccount> serviceAccounts;
    public String selfLink;
    public Scheduling scheduling;
    public String cpuPlatform;
    public Map<String, String> labels;
    public List<GuestAccelerator> guestAccelerators;
    public Boolean deletionProtection;
    public String reservationAffinity;
    public Tags tags;
    public String fingerprint;

    public static class NetworkInterface {
        public String name;
        public String network;
        public String networkIP;
        // ... other fields
    }

    public static class Disk {
        public String type;
        public String mode;
        public String source;
        // ... other fields
    }

    public static class Metadata {
        public String fingerprint;
        public Map<String, String> items;
    }

    public static class ServiceAccount {
        public String email;
        public List<String> scopes;
    }

    public static class Scheduling {
        public Boolean onHostMaintenance;
        public Boolean automaticRestart;
        // ... other fields
    }

    public static class GuestAccelerator {
        public String acceleratorType;
        public Integer acceleratorCount;
    }

    public static class Tags {
        public List<String> items;
        public String fingerprint;
    }
}

