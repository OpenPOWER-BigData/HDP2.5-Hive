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

package org.apache.hadoop.hive.ql.io.orc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Take the file types and the (optional) configuration column names/types and see if there
 * has been schema evolution.
 */
public class SchemaEvolution {
  private final TypeDescription[] readerFileTypes;
  private final boolean[] included;
  private final TypeDescription readerSchema;
  private boolean hasConversion;
  private static final Log LOG = LogFactory.getLog(SchemaEvolution.class);

  public SchemaEvolution(TypeDescription readerSchema, boolean[] included) {
    this.included = (included == null ? null : Arrays.copyOf(included, included.length));
    this.readerSchema = readerSchema;

    hasConversion = false;

    readerFileTypes = new TypeDescription[this.readerSchema.getMaximumId() + 1];
    buildSameSchemaFileTypesArray();
  }

  public SchemaEvolution(TypeDescription fileSchema,
                         TypeDescription readerSchema,
                         boolean[] included) throws IOException {
    this.included = (included == null ? null : Arrays.copyOf(included, included.length));
    if (checkAcidSchema(fileSchema)) {
      this.readerSchema = createEventSchema(readerSchema);
    } else {
      this.readerSchema = readerSchema;
    }

    hasConversion = false;
    readerFileTypes = new TypeDescription[this.readerSchema.getMaximumId() + 1];
    buildConversionFileTypesArray(fileSchema, this.readerSchema);
  }

  public TypeDescription getReaderSchema() {
    return readerSchema;
  }

  /**
   * Is there Schema Evolution data type conversion?
   * @return
   */
  public boolean hasConversion() {
    return hasConversion;
  }

  public TypeDescription getFileType(TypeDescription readerType) {
    return getFileType(readerType.getId());
  }

  /**
   * Get the file type by reader type id.
   * @param readerType
   * @return
   */
  public TypeDescription getFileType(int id) {
    return readerFileTypes[id];
  }

  void buildConversionFileTypesArray(TypeDescription fileType,
                                     TypeDescription readerType) throws IOException {
    // if the column isn't included, don't map it
    if (included != null && !included[readerType.getId()]) {
      return;
    }
    boolean isOk = true;
    // check the easy case first
    if (fileType.getCategory() == readerType.getCategory()) {
      switch (readerType.getCategory()) {
        case BOOLEAN:
        case BYTE:
        case SHORT:
        case INT:
        case LONG:
        case DOUBLE:
        case FLOAT:
        case STRING:
        case TIMESTAMP:
        case BINARY:
        case DATE:
          // these are always a match
          break;
        case CHAR:
        case VARCHAR:
          // We do conversion when same CHAR/VARCHAR type but different maxLength.
          if (fileType.getMaxLength() != readerType.getMaxLength()) {
            hasConversion = true;
          }
          break;
        case DECIMAL:
          // We do conversion when same DECIMAL type but different precision/scale.
          if (fileType.getPrecision() != readerType.getPrecision() ||
              fileType.getScale() != readerType.getScale()) {
            hasConversion = true;
          }
          break;
        case UNION:
        case MAP:
        case LIST: {
          // these must be an exact match
          List<TypeDescription> fileChildren = fileType.getChildren();
          List<TypeDescription> readerChildren = readerType.getChildren();
          if (fileChildren.size() == readerChildren.size()) {
            for(int i=0; i < fileChildren.size(); ++i) {
              buildConversionFileTypesArray(fileChildren.get(i), readerChildren.get(i));
            }
          } else {
            isOk = false;
          }
          break;
        }
        case STRUCT: {
          // allow either side to have fewer fields than the other
          List<TypeDescription> fileChildren = fileType.getChildren();
          List<TypeDescription> readerChildren = readerType.getChildren();
          if (fileChildren.size() != readerChildren.size()) {
            hasConversion = true;
          }
          int jointSize = Math.min(fileChildren.size(), readerChildren.size());
          for(int i=0; i < jointSize; ++i) {
            buildConversionFileTypesArray(fileChildren.get(i), readerChildren.get(i));
          }
          break;
        }
        default:
          throw new IllegalArgumentException("Unknown type " + readerType);
      }
    } else {
      /*
       * Check for the few cases where will not convert....
       */

      isOk = ConvertTreeReaderFactory.canConvert(fileType, readerType);
      hasConversion = true;
    }
    if (isOk) {
      int id = readerType.getId();
      if (readerFileTypes[id] != null) {
        throw new RuntimeException("reader to file type entry already assigned");
      }
      readerFileTypes[id] = fileType;
    } else {
      throw new IOException(
          String.format(
              "ORC does not support type conversion from file type %s (%d) to reader type %s (%d)",
              fileType.toString(), fileType.getId(),
              readerType.toString(), readerType.getId()));
    }
  }

  /**
   * Use to make a reader to file type array when the schema is the same.
   * @return
   */
  private void buildSameSchemaFileTypesArray() {
    buildSameSchemaFileTypesArrayRecurse(readerSchema);
  }

  void buildSameSchemaFileTypesArrayRecurse(TypeDescription readerType) {
    if (included != null && !included[readerType.getId()]) {
      return;
    }
    int id = readerType.getId();
    if (readerFileTypes[id] != null) {
      throw new RuntimeException("reader to file type entry already assigned");
    }
    readerFileTypes[id] = readerType;
    List<TypeDescription> children = readerType.getChildren();
    if (children != null) {
      for (TypeDescription child : children) {
        buildSameSchemaFileTypesArrayRecurse(child);
      }
    }
  }

  private static boolean checkAcidSchema(TypeDescription type) {
    if (type.getCategory().equals(TypeDescription.Category.STRUCT)) {
      List<String> rootFields = type.getFieldNames();
      if (acidEventFieldNames.equals(rootFields)) {
        return true;
      }
    }
    return false;
  }

  /**
   * @param typeDescr
   * @return ORC types for the ACID event based on the row's type description
   */
  public static TypeDescription createEventSchema(TypeDescription typeDescr) {
    TypeDescription result = TypeDescription.createStruct()
        .addField("operation", TypeDescription.createInt())
        .addField("originalTransaction", TypeDescription.createLong())
        .addField("bucket", TypeDescription.createInt())
        .addField("rowId", TypeDescription.createLong())
        .addField("currentTransaction", TypeDescription.createLong())
        .addField("row", typeDescr.clone());
    return result;
  }

  public static final List<String> acidEventFieldNames= new ArrayList<String>();
  static {
    acidEventFieldNames.add("operation");
    acidEventFieldNames.add("originalTransaction");
    acidEventFieldNames.add("bucket");
    acidEventFieldNames.add("rowId");
    acidEventFieldNames.add("currentTransaction");
    acidEventFieldNames.add("row");
  }
}
