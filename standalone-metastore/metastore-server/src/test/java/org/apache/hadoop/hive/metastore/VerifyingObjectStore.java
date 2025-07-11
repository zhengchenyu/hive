/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.metastore;

import static org.apache.commons.lang3.StringUtils.repeat;
import static org.apache.hadoop.hive.metastore.utils.StringUtils.normalizeIdentifier;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.ClassUtils;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.InvalidObjectException;
import org.apache.hadoop.hive.metastore.client.builder.GetPartitionsArgs;
import org.apache.hadoop.hive.metastore.model.MTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.hadoop.hive.metastore.api.ColumnStatistics;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.NoSuchObjectException;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.thrift.TException;

public class VerifyingObjectStore extends ObjectStore {
  private static final Logger LOG = LoggerFactory.getLogger(VerifyingObjectStore.class);

  public VerifyingObjectStore() {
    super();
    LOG.warn(getClass().getSimpleName() + " is being used - test run");
  }

  @Override
  public List<Partition> getPartitionsByFilter(String catName, String dbName, String tblName,
                                               GetPartitionsArgs args)
      throws MetaException, NoSuchObjectException {
    List<Partition> sqlResults = getPartitionsByFilterInternal(
        catName, dbName, tblName, true, false, args);
    List<Partition> ormResults = getPartitionsByFilterInternal(
        catName, dbName, tblName, false, true, args);
    verifyLists(sqlResults, ormResults, Partition.class);
    return sqlResults;
  }

  @Override
  public List<Partition> getPartitionsByNames(String catName, String dbName, String tblName,
      List<String> partNames) throws MetaException, NoSuchObjectException {
    GetPartitionsArgs args = new GetPartitionsArgs.GetPartitionsArgsBuilder().partNames(partNames).build();
    List<Partition> sqlResults = getPartitionsByNamesInternal(
        catName, dbName, tblName, true, false, args);
    List<Partition> ormResults = getPartitionsByNamesInternal(
        catName, dbName, tblName, false, true, args);
    verifyLists(sqlResults, ormResults, Partition.class);
    return sqlResults;
  }

  @Override
  public boolean getPartitionsByExpr(String catName, String dbName, String tblName, List<Partition> result,
      GetPartitionsArgs args) throws TException {
    List<Partition> ormParts = new LinkedList<>();
    boolean sqlResult = getPartitionsByExprInternal(
        catName, dbName, tblName, result, true, false, args);
    boolean ormResult = getPartitionsByExprInternal(
        catName, dbName, tblName, ormParts, false, true, args);
    if (sqlResult != ormResult) {
      String msg = "The unknown flag is different - SQL " + sqlResult + ", ORM " + ormResult;
      LOG.error(msg);
      throw new MetaException(msg);
    }
    verifyLists(result, ormParts, Partition.class);
    return sqlResult;
  }

  @Override
  public List<Partition> getPartitions(
      String catName, String dbName, String tableName, GetPartitionsArgs args) throws MetaException, NoSuchObjectException {
    openTransaction();
    List<Partition> sqlResults = getPartitionsInternal(catName, dbName, tableName, true, false, args);
    List<Partition> ormResults = getPartitionsInternal(catName, dbName, tableName, false, true, args);
    verifyLists(sqlResults, ormResults, Partition.class);
    commitTransaction();
    return sqlResults;
  }

  @Override
  public ColumnStatistics getTableColumnStatistics(String catName, String dbName,
      String tableName, List<String> colNames, String engine) throws MetaException, NoSuchObjectException {
    ColumnStatistics sqlResult = getTableColumnStatisticsInternal(
        catName, dbName, tableName, colNames, engine, true, false);
    ColumnStatistics jdoResult = getTableColumnStatisticsInternal(
        catName, dbName, tableName, colNames, engine, false, true);
    verifyObjects(sqlResult, jdoResult, ColumnStatistics.class);
    return sqlResult;
  }

  @Override
  public List<Partition> alterPartitions(String catName, String dbName, String tblName, List<List<String>> part_vals,
      List<Partition> newParts, long writeId, String queryWriteIdList) throws InvalidObjectException, MetaException {
    List<Partition> results = new ArrayList<>(newParts.size());
    catName = normalizeIdentifier(catName);
    dbName = normalizeIdentifier(dbName);
    tblName = normalizeIdentifier(tblName);
    boolean success = false;
    try {
      openTransaction();
      MTable table = ensureGetMTable(catName, dbName, tblName);
      if (writeId > 0) {
        newParts.forEach(newPart -> newPart.setWriteId(writeId));
      }
      List<FieldSchema> partCols = convertToFieldSchemas(table.getPartitionKeys());
      List<String> partNames = new ArrayList<>();
      for (List<String> partVal : part_vals) {
        partNames.add(Warehouse.makePartName(partCols, partVal));
      }
      List<Partition> oldParts = getPartitionsByNames(catName, dbName, tblName, partNames);
      if (oldParts.size() != partNames.size()) {
        throw new MetaException("Some partitions to be altered are missing");
      }
      List<Partition> tmpNewParts = new ArrayList<>(newParts);
      alterPartitionsInternal(table, partNames, newParts, queryWriteIdList, true, false);
      alterPartitionsInternal(table, partNames, oldParts, queryWriteIdList, false, true);
      results = alterPartitionsInternal(table, partNames, tmpNewParts, queryWriteIdList, true, false);
      // commit the changes
      success = commitTransaction();
    } catch (Exception exception) {
      LOG.error("Alter failed", exception);
      throw new MetaException(exception.getMessage());
    } finally {
      rollbackAndCleanup(success, null);
    }
    return results;
  }

  @Override
  public List<ColumnStatistics> getPartitionColumnStatistics(String catName, String dbName,
      String tableName, List<String> partNames, List<String> colNames, String engine)
      throws MetaException, NoSuchObjectException {
    List<ColumnStatistics> sqlResult = getPartitionColumnStatisticsInternal(
        catName, dbName, tableName, partNames, colNames, engine, true, false);
    List<ColumnStatistics> jdoResult = getPartitionColumnStatisticsInternal(
        catName, dbName, tableName, partNames, colNames, engine, false, true);
    verifyLists(sqlResult, jdoResult, ColumnStatistics.class);
    return sqlResult;
  }

  private void verifyObjects(
      Object sqlResult, Object jdoResult, Class<?> clazz) throws MetaException {
    if (EqualsBuilder.reflectionEquals(sqlResult, jdoResult)) return;
    StringBuilder errorStr = new StringBuilder("Objects are different: \n");
    try {
      dumpObject(errorStr, "SQL", sqlResult, clazz, 0);
      errorStr.append("\n");
      dumpObject(errorStr, "ORM", jdoResult, clazz, 0);
    } catch (Throwable t) {
      errorStr.append("Error getting the diff: " + t);
    }
    LOG.error("Different results: \n" + errorStr.toString());
    throw new MetaException("Different results from SQL and ORM, see log for details");
  }

  private <T> void verifyLists(Collection<T> sqlResults, Collection<T> ormResults,
      Class<?> clazz) throws MetaException {
    final int MAX_DIFFS = 5;
    if (sqlResults.size() != ormResults.size()) {
      String msg = "Lists are not the same size: SQL " + sqlResults.size()
          + ", ORM " + ormResults.size();
      LOG.error(msg);
      throw new MetaException(msg);
    }

    Iterator<T> sqlIter = sqlResults.iterator(), ormIter = ormResults.iterator();
    StringBuilder errorStr = new StringBuilder();
    int errors = 0;
    for (int partIx = 0; partIx < sqlResults.size(); ++partIx) {
      assert sqlIter.hasNext() && ormIter.hasNext();
      T p1 = sqlIter.next(), p2 = ormIter.next();
      if (EqualsBuilder.reflectionEquals(p1, p2)) continue;
      errorStr.append("Results are different at list index " + partIx + ": \n");
      try {
        dumpObject(errorStr, "SQL", p1, clazz, 0);
        errorStr.append("\n");
        dumpObject(errorStr, "ORM", p2, clazz, 0);
        errorStr.append("\n\n");
      } catch (Throwable t) {
        String msg = "Error getting the diff at list index " + partIx;
        errorStr.append("\n\n" + msg);
        LOG.error(msg, t);
        break;
      }
      if (++errors == MAX_DIFFS) {
        errorStr.append("\n\nToo many diffs, giving up (lists might be sorted differently)");
        break;
      }
    }
    if (errorStr.length() > 0) {
      LOG.error("Different results: \n" + errorStr.toString());
      throw new MetaException("Different results from SQL and ORM, see log for details");
    }
  }

  private static void dumpObject(StringBuilder errorStr, String name, Object p,
      Class<?> c, int level) throws IllegalAccessException {
    String offsetStr = repeat("  ", level);
    if (p == null || c == String.class || c.isPrimitive()
        || ClassUtils.wrapperToPrimitive(c) != null) {
      errorStr.append(offsetStr).append(name + ": [" + p + "]\n");
    } else if (ClassUtils.isAssignable(c, Iterable.class)) {
      errorStr.append(offsetStr).append(name + " is an iterable\n");
      Iterator<?> i1 = ((Iterable<?>)p).iterator();
      int i = 0;
      while (i1.hasNext()) {
        Object o1 = i1.next();
        Class<?> t = o1 == null ? Object.class : o1.getClass(); // ...
        dumpObject(errorStr, name + "[" + (i++) + "]", o1, t, level + 1);
      }
    } else if (c.isArray()) {
      int len = Array.getLength(p);
      Class<?> t = c.getComponentType();
      errorStr.append(offsetStr).append(name + " is an array\n");
      for (int i = 0; i < len; ++i) {
        dumpObject(errorStr, name + "[" + i + "]", Array.get(p, i), t, level + 1);
      }
    } else if (ClassUtils.isAssignable(c, Map.class)) {
      Map<?,?> c1 = (Map<?,?>)p;
      errorStr.append(offsetStr).append(name + " is a map\n");
      dumpObject(errorStr, name + ".keys", c1.keySet(), Set.class, level + 1);
      dumpObject(errorStr, name + ".vals", c1.values(), Collection.class, level + 1);
    } else {
      errorStr.append(offsetStr).append(name + " is of type " + c.getCanonicalName() + "\n");
      // TODO: this doesn't include superclass.
      Field[] fields = c.getDeclaredFields();
      AccessibleObject.setAccessible(fields, true);
      for (int i = 0; i < fields.length; i++) {
        Field f = fields[i];
        if (f.getName().indexOf('$') != -1 || Modifier.isStatic(f.getModifiers())) continue;
        dumpObject(errorStr, name + "." + f.getName(), f.get(p), f.getType(), level + 1);
      }
    }
  }
}
