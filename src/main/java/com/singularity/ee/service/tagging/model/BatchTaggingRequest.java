package com.singularity.ee.service.tagging.model;

import com.singularity.ee.agent.appagent.kernel.spi.IConfigurationChannel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BatchTaggingRequest {
    public String entityType;
    public String source = "API";
    public List<Entity> entities;
    public transient EntityType cmdbType;
    public transient int retries = 0;

    public BatchTaggingRequest( EntityType type ) {
        entityType = type.convertToAPIEntityType();
        this.entities = new ArrayList<>();
    }

    public BatchTaggingRequest( EntityType type, Entity entity ) {
        this(type);
        this.entities.add(entity);
    }

    public BatchTaggingRequest (GCEInstance gceInstance, IConfigurationChannel iConfigChannel) {
        this.entityType = EntityType.Node.convertToAPIEntityType();
        Map<String,String> map = new HashMap<>();
        map.put("GCE|id", gceInstance.id);
        map.put("GCE|name", gceInstance.name);
        map.put("GCE|description", gceInstance.description);
        map.put("GCE|zone", gceInstance.zone);
        map.put("GCE|machineType", gceInstance.machineType);
        map.put("GCE|status", gceInstance.status);
        map.put("GCE|statusMessage", gceInstance.statusMessage);
        map.put("GCE|selfLink", gceInstance.selfLink);
        map.put("GCE|cpuPlatform", gceInstance.cpuPlatform);
        map.put("GCE|reservationAffinity", gceInstance.reservationAffinity);
        for(Map.Entry entry : gceInstance.labels.entrySet())
            map.put("GCE|Label|"+entry.getKey(), (String) entry.getValue());
        addEntity(iConfigChannel.getComponentNodeName(), iConfigChannel.getNodeID(), map);
    }

    public void addEntity (String entityName, Long entityId, Map<String, String> tags) {
        Entity entity = new Entity(entityName, entityId);
        for( String key : tags.keySet())
            entity.tags.add(new Tag(key, tags.get(key)));
        this.entities.add(entity);
    }

}
