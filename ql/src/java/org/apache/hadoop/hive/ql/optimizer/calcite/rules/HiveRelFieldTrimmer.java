/**
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
package org.apache.hadoop.hive.ql.optimizer.calcite.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.calcite.linq4j.Ord;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.RelFactories;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql2rel.RelFieldTrimmer;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.Util;
import org.apache.hadoop.hive.ql.metadata.Table;
import org.apache.hadoop.hive.ql.optimizer.calcite.reloperators.HiveProject;
import org.apache.hadoop.hive.ql.parse.ColumnAccessInfo;

import com.google.common.collect.ImmutableList;

public class HiveRelFieldTrimmer extends RelFieldTrimmer {

  public HiveRelFieldTrimmer(SqlValidator validator) {
    super(validator);
  }

  public HiveRelFieldTrimmer(SqlValidator validator,
      RelFactories.ProjectFactory projectFactory,
      RelFactories.FilterFactory filterFactory,
      RelFactories.JoinFactory joinFactory,
      RelFactories.SemiJoinFactory semiJoinFactory,
      RelFactories.SortFactory sortFactory,
      RelFactories.AggregateFactory aggregateFactory,
      RelFactories.SetOpFactory setOpFactory) {
    super(validator, projectFactory, filterFactory, joinFactory,
            semiJoinFactory, sortFactory, aggregateFactory, setOpFactory);
  }

  private ColumnAccessInfo columnAccessInfo;

  private Map<HiveProject, Table> viewProjectToTableSchema;

  public HiveRelFieldTrimmer(SqlValidator validator, RelFactories.ProjectFactory projectFactory,
      RelFactories.FilterFactory filterFactory, RelFactories.JoinFactory joinFactory,
      RelFactories.SemiJoinFactory semiJoinFactory, RelFactories.SortFactory sortFactory,
      RelFactories.AggregateFactory aggregateFactory, RelFactories.SetOpFactory setOpFactory,
      ColumnAccessInfo columnAccessInfo, Map<HiveProject, Table> viewToTableSchema) {
    super(validator, projectFactory, filterFactory, joinFactory, semiJoinFactory, sortFactory,
        aggregateFactory, setOpFactory);
    this.columnAccessInfo = columnAccessInfo;
    this.viewProjectToTableSchema = viewToTableSchema;
  }

  protected TrimResult trimChild(
      RelNode rel,
      RelNode input,
      ImmutableBitSet fieldsUsed,
      Set<RelDataTypeField> extraFields) {
    Util.discard(rel);
    if (input.getClass().getName().endsWith("MedMdrClassExtentRel")) {
      // MedMdrJoinRule cannot handle Join of Project of
      // MedMdrClassExtentRel, only naked MedMdrClassExtentRel.
      // So, disable trimming.
      fieldsUsed = ImmutableBitSet.range(input.getRowType().getFieldCount());
    }
    final ImmutableList<RelCollation> collations =
        RelMetadataQuery.collations(input);
    for (RelCollation collation : collations) {
      for (RelFieldCollation fieldCollation : collation.getFieldCollations()) {
        fieldsUsed = fieldsUsed.set(fieldCollation.getFieldIndex());
      }
    }
    return dispatchTrimFields(input, fieldsUsed, extraFields);
  }

  /**
   * Variant of {@link #trimFields(RelNode, ImmutableBitSet, Set)} for
   * {@link org.apache.calcite.rel.logical.LogicalProject}.
   */
  public TrimResult trimFields(Project project, ImmutableBitSet fieldsUsed,
      Set<RelDataTypeField> extraFields) {
    // set columnAccessInfo for ViewColumnAuthorization
    for (Ord<RexNode> ord : Ord.zip(project.getProjects())) {
      if (fieldsUsed.get(ord.i)) {
        if (this.columnAccessInfo != null && this.viewProjectToTableSchema != null
            && this.viewProjectToTableSchema.containsKey(project)) {
          Table tab = this.viewProjectToTableSchema.get(project);
          this.columnAccessInfo.add(tab.getCompleteName(), tab.getCols().get(ord.i).getName());
        }
      }
    }
    return super.trimFields(project, fieldsUsed, extraFields);
  }

}
