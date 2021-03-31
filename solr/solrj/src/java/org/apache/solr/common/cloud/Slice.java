/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.common.cloud;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.apache.solr.common.util.Utils.toJSONString;

/**
 * A Slice contains immutable information about a logical shard (all replicas that share the same shard id).
 */
public class Slice extends ZkNodeProps implements Iterable<Replica> {
  public final String collection;
  private final HashMap<String,Replica> idToReplica;
  private Long collectionId;

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    Slice slice = (Slice) o;
    return collection.equals(slice.collection) && collectionId.equals(slice.collectionId) && name.equals(slice.name);
  }

  @Override
  public int hashCode() {
    return hashcode;
  }

  /** Loads multiple slices into a Map from a generic Map that probably came from deserialized JSON. */
  public static Map<String,Slice> loadAllFromMap(String collection, long id, Map<String, Object> genericSlices) {
    if (genericSlices == null) return Collections.emptyMap();
    Map<String, Slice> result = new LinkedHashMap<>(genericSlices.size());
    genericSlices.forEach((name, val) -> {
      if (val instanceof Slice) {
        result.put(name, (Slice) val);
      } else if (val instanceof Map) {
        result.put(name, new Slice(name, null, (Map<String,Object>) val, collection, id));
      }
    });
    return result;
  }

  @Override
  public Iterator<Replica> iterator() {
    return replicas.values().iterator();
  }

  public Replica getReplicaById(String id) {
    return idToReplica.get(id);
  }

  public Map<String,Replica> getReplicaByIds() {
    return idToReplica;
  }

//  public Slice copy() {
//    return copy(Collections.emptyMap());
//  }

  public Slice copyWithReplicas(Map<String,Replica> replicasCopy) {
    Slice s = new Slice(name, replicasCopy, this, getName(), collectionId);
    return s;
  }

  /** The slice's state. */
  public enum State {

    /** The normal/default state of a shard. */
    ACTIVE,

    /**
     * A shard is put in that state after it has been successfully split. See
     * <a href="https://lucene.apache.org/solr/guide/collections-api.html#splitshard">
     * the reference guide</a> for more details.
     */
    INACTIVE,

    /**
     * When a shard is split, the new sub-shards are put in that state while the
     * split operation is in progress. It's also used when the shard is undergoing data restoration.
     * A shard in this state still receives
     * update requests from the parent shard leader, however does not participate
     * in distributed search.
     */
    CONSTRUCTION,

    /**
     * Sub-shards of a split shard are put in that state, when they need to
     * create replicas in order to meet the collection's replication factor. A
     * shard in that state still receives update requests from the parent shard
     * leader, however does not participate in distributed search.
     */
    RECOVERY,

    /**
     * Sub-shards of a split shard are put in that state when the split is deemed failed
     * by the overseer even though all replicas are active because either the leader node is
     * no longer live or has a different ephemeral owner (zk session id). Such conditions can potentially
     * lead to data loss. See SOLR-9438 for details. A shard in that state will neither receive
     * update requests from the parent shard leader, nor participate in distributed search.
     */
    RECOVERY_FAILED;

    @Override
    public String toString() {
      return super.toString().toLowerCase(Locale.ROOT);
    }

    /** Converts the state string to a State instance. */
    public static State getState(String stateStr) {
      return State.valueOf(stateStr.toUpperCase(Locale.ROOT));
    }
  }

  public static final String REPLICAS = "replicas";
  public static final String RANGE = "range";
  public static final String LEADER = "leader";       // FUTURE: do we want to record the leader as a slice property in the JSON (as opposed to isLeader as a replica property?)
  public static final String PARENT = "parent";

  private final String name;
  private final DocRouter.Range range;
  private final Integer replicationFactor;      // FUTURE: optional per-slice override of the collection replicationFactor
  private final Map<String,Replica> replicas;
  private Replica leader;
  private State state;
  private final String parent;
  private final Map<String, RoutingRule> routingRules;

  private final int hashcode;

  /**
   * @param name  The name of the slice
   * @param replicas The replicas of the slice.  This is used directly and a copy is not made.  If null, replicas will be constructed from props.
   * @param props  The properties of the slice - a shallow copy will always be made.
   */
  public Slice(String name, Map<String,Replica> replicas, Map<String,Object> props, String collection, Long collectionId) {
    super(props == null ? new LinkedHashMap(2) : new LinkedHashMap<>(props));
    this.name = name;
    this.collection = collection;
    Object rangeObj = get(RANGE);
    if (get(ZkStateReader.STATE_PROP) != null) {
      this.state = State.getState((String) get(ZkStateReader.STATE_PROP));
    } else {
      this.state = State.ACTIVE;                         //Default to ACTIVE
      put(ZkStateReader.STATE_PROP, state.toString());
    }
    DocRouter.Range tmpRange = null;
    if (rangeObj instanceof DocRouter.Range) {
      tmpRange = (DocRouter.Range) rangeObj;
    } else if (rangeObj != null) {
      // Doesn't support custom implementations of Range, but currently not needed.
      tmpRange = DocRouter.DEFAULT.fromString(rangeObj.toString());
    }
    range = tmpRange;

    /** debugging.  this isn't an error condition for custom sharding.
     if (range == null) {
     System.out.println("###### NO RANGE for " + name + " props=" + props);
     }
     **/

    if (containsKey(PARENT) && get(PARENT) != null) this.parent = (String) get(PARENT);
    else this.parent = null;

    replicationFactor = null;  // future

    // add the replicas *after* the other properties (for aesthetics, so it's easy to find slice properties in the JSON output)
    this.replicas = replicas != null ? replicas : makeReplicas(collection, collectionId, name, (Map<String,Object>) get(REPLICAS));

    this.idToReplica = new HashMap<>(this.replicas.size());

    this.replicas.forEach((s, replica) -> {
      String id = replica.getId();
      if (id != null) {
        this.idToReplica.put(id, replica);
      } else {
        throw new IllegalStateException("no id found in replica");
      }
    });

    put(REPLICAS, this.replicas);

    Map<String,Object> rules = (Map<String,Object>) get("routingRules");
    if (rules != null) {
      this.routingRules = new HashMap<>();
      rules.forEach((key, o) -> {
        if (o instanceof Map) {
          Map map = (Map) o;
          RoutingRule rule = new RoutingRule(key, map);
          routingRules.put(key, rule);
        } else {
          routingRules.put(key, (RoutingRule) o);
        }
      });
    } else {
      this.routingRules = null;
    }
    this.collectionId = collectionId;

    if (this.collectionId == null) {
      Object collId = props.get("collId");
      if (collId != null) {
        this.collectionId = Long.parseLong((String) collId);
      }
    } else {
      put("collId", Long.toString(collectionId));
    }

    leader = findLeader();
    hashcode = Objects.hash(collection, collectionId, name);
  }


  private Map<String,Replica> makeReplicas(String collection, Long collectionId, String slice,Map<String,Object> genericReplicas) {
    if (genericReplicas == null) return new HashMap<>(1);
    Map<String,Replica> result = new LinkedHashMap<>(genericReplicas.size());
    genericReplicas.forEach((name, val) -> {
      Replica r;
      if (val instanceof Replica) {
        r = (Replica) val;
      } else {
        r = new Replica(name, (Map<String,Object>) val, collection, collectionId, slice);
      }
      result.put(name, r);
    });
    return result;
  }

  private Replica findLeader() {
    for (Replica replica : replicas.values()) {
      String leaderStr = replica.getStr(LEADER);
      if (leaderStr != null && leaderStr.equals("true")) {
        return replica;
      }
    }
    return null;
  }

  public String getCollection() {
    return collection;
  }
  /**
   * Return slice name (shard id).
   */
  public String getName() {
    return name;
  }

  /**
   * Gets the list of all replicas for this slice.
   */
  public Collection<Replica> getReplicas() {
    return replicas.values();
  }

  /**
   * Gets all replicas that match a predicate
   */
  public List<Replica> getReplicas(Predicate<Replica> pred) {
    return replicas.values().stream().filter(pred).collect(Collectors.toList());
  }

  /**
   * Gets the list of replicas that have a type present in s
   */
  public List<Replica> getReplicas(EnumSet<Replica.Type> s) {
    return this.getReplicas(r->s.contains(r.getType()));
  }

  /**
   * Get the map of coreNodeName to replicas for this slice.
   */
  public Map<String, Replica> getReplicasMap() {
    return replicas;
  }

  public Map<String,Replica> getReplicasCopy() {
    return new LinkedHashMap<>(replicas);
  }

  public Replica getLeader() {
    return leader;
  }

  public Replica getReplica(String replicaName) {
    return replicas.get(replicaName);
  }

  public DocRouter.Range getRange() {
    return range;
  }

  public State getState() {
    return state;
  }

  public String getParent() {
    return parent;
  }

  public Map<String, RoutingRule> getRoutingRules() {
    return routingRules;
  }

  @Override
  public String toString() {
    return name + "[" + leader + "]"  + ':' + toJSONString(this);
  }

}
