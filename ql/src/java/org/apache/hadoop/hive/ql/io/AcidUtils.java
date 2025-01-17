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

package org.apache.hadoop.hive.ql.io;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.apache.hadoop.hive.common.ValidTxnList;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.conf.HiveConf.ConfVars;
import org.apache.hadoop.hive.metastore.api.DataOperationType;
import org.apache.hadoop.hive.metastore.api.hive_metastoreConstants;
import org.apache.hadoop.hive.ql.ErrorMsg;
import org.apache.hadoop.hive.ql.metadata.Table;
import org.apache.hadoop.hive.shims.HadoopShims;
import org.apache.hadoop.hive.shims.ShimLoader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Utilities that are shared by all of the ACID input and output formats. They
 * are used by the compactor and cleaner and thus must be format agnostic.
 */
public class AcidUtils {
  // This key will be put in the conf file when planning an acid operation
  public static final String CONF_ACID_KEY = "hive.doing.acid";
  public static final String BASE_PREFIX = "base_";
  public static final PathFilter baseFileFilter = new PathFilter() {
    @Override
    public boolean accept(Path path) {
      return path.getName().startsWith(BASE_PREFIX);
    }
  };
  public static final String DELTA_PREFIX = "delta_";
  public static final String DELTA_SIDE_FILE_SUFFIX = "_flush_length";
  public static final PathFilter deltaFileFilter = new PathFilter() {
    @Override
    public boolean accept(Path path) {
      return path.getName().startsWith(DELTA_PREFIX);
    }
  };
  public static final String BUCKET_PREFIX = "bucket_";
  public static final PathFilter bucketFileFilter = new PathFilter() {
    @Override
    public boolean accept(Path path) {
      return path.getName().startsWith(BUCKET_PREFIX) &&
          !path.getName().endsWith(DELTA_SIDE_FILE_SUFFIX);
    }
  };
  public static final String BUCKET_DIGITS = "%05d";
  public static final String LEGACY_FILE_BUCKET_DIGITS = "%06d";
  public static final String DELTA_DIGITS = "%07d";
  public static final Pattern BUCKET_DIGIT_PATTERN = Pattern.compile("[0-9]{5}$");
  public static final Pattern   LEGACY_BUCKET_DIGIT_PATTERN = Pattern.compile("^[0-9]{6}");
  public static final PathFilter originalBucketFilter = new PathFilter() {
    @Override
    public boolean accept(Path path) {
      return ORIGINAL_PATTERN.matcher(path.getName()).matches();
    }
  };

  private AcidUtils() {
    // NOT USED
  }
  private static final Log LOG = LogFactory.getLog(AcidUtils.class.getName());

  private static final Pattern ORIGINAL_PATTERN =
      Pattern.compile("[0-9]+_[0-9]+");

  public static final PathFilter hiddenFileFilter = new PathFilter(){
    @Override
    public boolean accept(Path p){
      String name = p.getName();
      return !name.startsWith("_") && !name.startsWith(".");
    }
  };

  private static final HadoopShims SHIMS = ShimLoader.getHadoopShims();

  /**
   * Create the bucket filename.
   * @param subdir the subdirectory for the bucket.
   * @param bucket the bucket number
   * @return the filename
   */
  public static Path createBucketFile(Path subdir, int bucket) {
    return new Path(subdir,
        BUCKET_PREFIX + String.format(BUCKET_DIGITS, bucket));
  }

  public static String deltaSubdir(long min, long max) {
    return DELTA_PREFIX + String.format(DELTA_DIGITS, min) + "_" +
        String.format(DELTA_DIGITS, max);
  }

  public static String baseDir(long txnId) {
    return BASE_PREFIX + String.format(DELTA_DIGITS, txnId);
  }
  /**
   * Create a filename for a bucket file.
   * @param directory the partition directory
   * @param options the options for writing the bucket
   * @return the filename that should store the bucket
   */
  public static Path createFilename(Path directory,
                                    AcidOutputFormat.Options options) {
    String subdir;
    if (options.getOldStyle()) {
      return new Path(directory, String.format(LEGACY_FILE_BUCKET_DIGITS,
          options.getBucket()) + "_0");
    } else if (options.isWritingBase()) {
      subdir = BASE_PREFIX + String.format(DELTA_DIGITS,
          options.getMaximumTransactionId());
    } else {
      subdir = deltaSubdir(options.getMinimumTransactionId(),
          options.getMaximumTransactionId());
    }
    return createBucketFile(new Path(directory, subdir), options.getBucket());
  }

  /**
   * Get the transaction id from a base directory name.
   * @param path the base directory name
   * @return the maximum transaction id that is included
   */
  static long parseBase(Path path) {
    String filename = path.getName();
    if (filename.startsWith(BASE_PREFIX)) {
      return Long.parseLong(filename.substring(BASE_PREFIX.length()));
    }
    throw new IllegalArgumentException(filename + " does not start with " +
        BASE_PREFIX);
  }

  /**
   * Parse a bucket filename back into the options that would have created
   * the file.
   * @param bucketFile the path to a bucket file
   * @param conf the configuration
   * @return the options used to create that filename
   */
  public static AcidOutputFormat.Options
                    parseBaseBucketFilename(Path bucketFile,
                                            Configuration conf) {
    AcidOutputFormat.Options result = new AcidOutputFormat.Options(conf);
    String filename = bucketFile.getName();
    result.writingBase(true);
    if (ORIGINAL_PATTERN.matcher(filename).matches()) {
      int bucket =
          Integer.parseInt(filename.substring(0, filename.indexOf('_')));
      result
          .setOldStyle(true)
          .minimumTransactionId(0)
          .maximumTransactionId(0)
          .bucket(bucket);
    } else if (filename.startsWith(BUCKET_PREFIX)) {
      int bucket =
          Integer.parseInt(filename.substring(filename.indexOf('_') + 1));
      result
          .setOldStyle(false)
          .minimumTransactionId(0)
          .maximumTransactionId(parseBase(bucketFile.getParent()))
          .bucket(bucket);
    } else {
      result.setOldStyle(true).bucket(-1).minimumTransactionId(0)
          .maximumTransactionId(0);
    }
    return result;
  }

  public enum Operation {
    NOT_ACID, INSERT, UPDATE, DELETE;
  }

  /**
   * Logically this should have been defined in Operation but that causes a dependency
   * on metastore package from exec jar (from the cluster) which is not allowed.
   * This method should only be called from client side where metastore.* classes are present.
   * Not following this will not be caught by unit tests since they have all the jar loaded.
   */
  public static DataOperationType toDataOperationType(Operation op) {
    switch (op) {
      case NOT_ACID:
        return DataOperationType.UNSET;
      case INSERT:
        return DataOperationType.INSERT;
      case UPDATE:
        return DataOperationType.UPDATE;
      case DELETE:
        return DataOperationType.DELETE;
      default:
        throw new IllegalArgumentException("Unexpected Operation: " + op);
    }
  }

  public static interface Directory {

    /**
     * Get the base directory.
     * @return the base directory to read
     */
    Path getBaseDirectory();

    /**
     * Get the list of original files.  Not {@code null}.
     * @return the list of original files (eg. 000000_0)
     */
    List<FileStatus> getOriginalFiles();

    /**
     * Get the list of base and delta directories that are valid and not
     * obsolete.  Not {@code null}.  List must be sorted in a specific way.
     * See {@link org.apache.hadoop.hive.ql.io.AcidUtils.ParsedDelta#compareTo(org.apache.hadoop.hive.ql.io.AcidUtils.ParsedDelta)}
     * for details.
     * @return the minimal list of current directories
     */
    List<ParsedDelta> getCurrentDirectories();

    /**
     * Get the list of obsolete directories. After filtering out bases and
     * deltas that are not selected by the valid transaction list, return the
     * list of original files, bases, and deltas that have been replaced by
     * more up to date ones.  Not {@code null}.
     */
    List<FileStatus> getObsolete();
  }

  public static class ParsedDelta implements Comparable<ParsedDelta> {
    final long minTransaction;
    final long maxTransaction;
    final FileStatus path;

    ParsedDelta(long min, long max, FileStatus path) {
      this.minTransaction = min;
      this.maxTransaction = max;
      this.path = path;
    }

    public long getMinTransaction() {
      return minTransaction;
    }

    public long getMaxTransaction() {
      return maxTransaction;
    }

    public Path getPath() {
      return path.getPath();
    }

    @Override
    public int compareTo(ParsedDelta parsedDelta) {
      if (minTransaction != parsedDelta.minTransaction) {
        if (minTransaction < parsedDelta.minTransaction) {
          return -1;
        } else {
          return 1;
        }
      } else if (maxTransaction != parsedDelta.maxTransaction) {
        if (maxTransaction < parsedDelta.maxTransaction) {
          return 1;
        } else {
          return -1;
        }
      } else {
        return path.compareTo(parsedDelta.path);
      }
    }
  }

  /**
   * Convert a list of deltas to a list of delta directories.
   * @param deltas the list of deltas out of a Directory object.
   * @return a list of delta directory paths that need to be read
   */
  public static Path[] getPaths(List<ParsedDelta> deltas) {
    Path[] result = new Path[deltas.size()];
    for(int i=0; i < result.length; ++i) {
      result[i] = deltas.get(i).getPath();
    }
    return result;
  }

  /**
   * Convert the list of deltas into an equivalent list of begin/end
   * transaction id pairs.
   * @param deltas
   * @return the list of transaction ids to serialize
   */
  public static List<Long> serializeDeltas(List<ParsedDelta> deltas) {
    List<Long> result = new ArrayList<Long>(deltas.size() * 2);
    for(ParsedDelta delta: deltas) {
      result.add(delta.minTransaction);
      result.add(delta.maxTransaction);
    }
    return result;
  }

  /**
   * Convert the list of begin/end transaction id pairs to a list of delta
   * directories.
   * @param root the root directory
   * @param deltas list of begin/end transaction id pairs
   * @return the list of delta paths
   */
  public static Path[] deserializeDeltas(Path root, List<Long> deltas) {
    int deltaSize = deltas.size() / 2;
    Path[] result = new Path[deltaSize];
    for(int i = 0; i < deltaSize; ++i) {
      result[i] = new Path(root, deltaSubdir(deltas.get(i * 2),
          deltas.get(i * 2 + 1)));
    }
    return result;
  }

  static ParsedDelta parseDelta(FileStatus path) {
    String filename = path.getPath().getName();
    if (filename.startsWith(DELTA_PREFIX)) {
      String rest = filename.substring(DELTA_PREFIX.length());
      int split = rest.indexOf('_');
      long min = Long.parseLong(rest.substring(0, split));
      long max = Long.parseLong(rest.substring(split + 1));
      return new ParsedDelta(min, max, path);
    }
    throw new IllegalArgumentException(path + " does not start with " +
                                       DELTA_PREFIX);
  }

  /**
   * Is the given directory in ACID format?
   * @param directory the partition directory to check
   * @param conf the query configuration
   * @return true, if it is an ACID directory
   * @throws IOException
   */
  public static boolean isAcid(Path directory,
                               Configuration conf) throws IOException {
    FileSystem fs = directory.getFileSystem(conf);
    for(FileStatus file: fs.listStatus(directory)) {
      String filename = file.getPath().getName();
      if (filename.startsWith(BASE_PREFIX) ||
          filename.startsWith(DELTA_PREFIX)) {
        if (file.isDir()) {
          return true;
        }
      }
    }
    return false;
  }

  public static Directory getAcidState(Path directory,
      Configuration conf,
      ValidTxnList txnList
      ) throws IOException {
    return getAcidState(directory, conf, txnList, false);
  }

  /**
   * Get the ACID state of the given directory. It finds the minimal set of
   * base and diff directories. Note that because major compactions don't
   * preserve the history, we can't use a base directory that includes a
   * transaction id that we must exclude.
   * @param directory the partition directory to analyze
   * @param conf the configuration
   * @param txnList the list of transactions that we are reading
   * @return the state of the directory
   * @throws IOException
   */
  public static Directory getAcidState(Path directory,
                                       Configuration conf,
                                       ValidTxnList txnList,
                                       boolean ignoreEmptyFiles
                                       ) throws IOException {
    FileSystem fs = directory.getFileSystem(conf);
    FileStatus bestBase = null;
    long bestBaseTxn = 0;
    long oldestBaseTxnId = Long.MAX_VALUE;
    Path oldestBase = null;
    final List<ParsedDelta> deltas = new ArrayList<ParsedDelta>();
    List<ParsedDelta> working = new ArrayList<ParsedDelta>();
    List<FileStatus> originalDirectories = new ArrayList<FileStatus>();
    final List<FileStatus> original = new ArrayList<FileStatus>();
    final List<FileStatus> obsolete = new ArrayList<FileStatus>();
    List<FileStatus> children = SHIMS.listLocatedStatus(fs, directory,
        hiddenFileFilter);
    for(FileStatus child: children) {
      Path p = child.getPath();
      String fn = p.getName();
      if (fn.startsWith(BASE_PREFIX) && child.isDir()) {
        long txn = parseBase(p);
        if(oldestBaseTxnId > txn) {
          //keep track for error reporting
          oldestBase = p;
          oldestBaseTxnId = txn;
        }
        if (bestBase == null) {
          if(isValidBase(txn, txnList)) {
            bestBase = child;
            bestBaseTxn = txn;
          }
        } else if (bestBaseTxn < txn) {
          if(isValidBase(txn, txnList)) {
            obsolete.add(bestBase);
            bestBase = child;
            bestBaseTxn = txn;
          }
        } else {
          obsolete.add(child);
        }
      } else if (fn.startsWith(DELTA_PREFIX) && child.isDir()) {
        ParsedDelta delta = parseDelta(child);
        if (txnList.isTxnRangeValid(delta.minTransaction,
            delta.maxTransaction) !=
            ValidTxnList.RangeResponse.NONE) {
          working.add(delta);
        }
      } else if (child.isDir()) {
        // This is just the directory.  We need to recurse and find the actual files.  But don't
        // do this until we have determined there is no base.  This saves time.  Plus,
        // it is possible that the cleaner is running and removing these original files,
        // in which case recursing through them could cause us to get an error.
        originalDirectories.add(child);
      } else  if (!ignoreEmptyFiles || child.getLen() != 0) {
        original.add(child);
      }
    }

    // if we have a base, the original files are obsolete.
    if (bestBase != null) {
      // Add original files to obsolete list if any
      obsolete.addAll(original);
      // Add original direcotries to obsolete list if any
      obsolete.addAll(originalDirectories);
      // remove the entries so we don't get confused later and think we should
      // use them.
      original.clear();
      originalDirectories.clear();
    } else {
      // Okay, we're going to need these originals.  Recurse through them and figure out what we
      // really need.
      for (FileStatus origDir : originalDirectories) {
        findOriginals(fs, origDir, original);
      }
    }

    Collections.sort(working);
    //so now, 'working' should be sorted like delta_5_20 delta_5_10 delta_11_20 delta_51_60 for example
    //and we want to end up with the best set containing all relevant data: delta_5_20 delta_51_60,
    //subject to list of 'exceptions' in 'txnList' (not show in above example).
    long current = bestBaseTxn;
    for(ParsedDelta next: working) {
      if (next.maxTransaction > current) {
        // are any of the new transactions ones that we care about?
        if (txnList.isTxnRangeValid(current+1, next.maxTransaction) !=
            ValidTxnList.RangeResponse.NONE) {
          deltas.add(next);
          current = next.maxTransaction;
        }
      } else {
        obsolete.add(next.path);
      }
    }

    if(oldestBase != null && bestBase == null) {
      /**
       * If here, it means there was a base_x (> 1 perhaps) but none were suitable for given
       * {@link txnList}.  Note that 'original' files are logically a base_Long.MIN_VALUE and thus
       * cannot have any data for an open txn.  We could check {@link deltas} has files to cover
       * [1,n] w/o gaps but this would almost never happen...*/
      //todo: this should only care about 'open' tnxs (HIVE-14211)
      long[] exceptions = txnList.getInvalidTransactions();
      String minOpenTxn = exceptions != null && exceptions.length > 0 ?
        Long.toString(exceptions[0]) : "x";
      throw new IOException(ErrorMsg.ACID_NOT_ENOUGH_HISTORY.format(
        Long.toString(txnList.getHighWatermark()),
        minOpenTxn, oldestBase.toString()));
    }
    final Path base = bestBase == null ? null : bestBase.getPath();
    LOG.debug("in directory " + directory.toUri().toString() + " base = " + base + " deltas = " +
        deltas.size());

    return new Directory(){

      @Override
      public Path getBaseDirectory() {
        return base;
      }

      @Override
      public List<FileStatus> getOriginalFiles() {
        return original;
      }

      @Override
      public List<ParsedDelta> getCurrentDirectories() {
        return deltas;
      }

      @Override
      public List<FileStatus> getObsolete() {
        return obsolete;
      }
    };
  }
  /**
   * We can only use a 'base' if it doesn't have an open txn (from specific reader's point of view)
   * A 'base' with open txn in its range doesn't have 'enough history' info to produce a correct
   * snapshot for this reader.
   * Note that such base is NOT obsolete.  Obsolete files are those that are "covered" by other
   * files within the snapshot.
   */
  private static boolean isValidBase(long baseTxnId, ValidTxnList txnList) {
    /*This implementation is suboptimal.  It considers open/aborted txns invalid while we are only
     * concerned with 'open' ones.  (Compaction removes any data that belongs to aborted txns and
     * reads skip anything that belongs to aborted txn, thus base_7 is still OK if the only exception
     * is txn 5 which is aborted).  So this implementation can generate false positives. (HIVE-14211)
     * */
    if(baseTxnId == Long.MIN_VALUE) {
      //such base is created by 1st compaction in case of non-acid to acid table conversion
      //By definition there are no open txns with id < 1.
      return true;
    }
    return txnList.isValidBase(baseTxnId);
  }
 
  /**
   * Find the original files (non-ACID layout) recursively under the partition
   * directory.
   * @param fs the file system
   * @param stat the file/directory to add
   * @param original the list of original files
   * @throws IOException
   */
  private static void findOriginals(FileSystem fs, FileStatus stat,
                                    List<FileStatus> original
                                    ) throws IOException {
    if (stat.isDir()) {
      for(FileStatus child: SHIMS.listLocatedStatus(fs, stat.getPath(), hiddenFileFilter)) {
        findOriginals(fs, child, original);
      }
    } else {
      original.add(stat);
    }
  }

  public static boolean isTablePropertyTransactional(Properties props) {
    String resultStr = props.getProperty(hive_metastoreConstants.TABLE_IS_TRANSACTIONAL);
    if (resultStr == null) {
      resultStr = props.getProperty(hive_metastoreConstants.TABLE_IS_TRANSACTIONAL.toUpperCase());
    }
    return resultStr != null && resultStr.equalsIgnoreCase("true");
  }

  public static boolean isTablePropertyTransactional(Map<String, String> parameters) {
    String resultStr = parameters.get(hive_metastoreConstants.TABLE_IS_TRANSACTIONAL);
    if (resultStr == null) {
      resultStr = parameters.get(hive_metastoreConstants.TABLE_IS_TRANSACTIONAL.toUpperCase());
    }
    return resultStr != null && resultStr.equalsIgnoreCase("true");
  }

  public static boolean isTablePropertyTransactional(Configuration conf) {
    String resultStr = conf.get(hive_metastoreConstants.TABLE_IS_TRANSACTIONAL);
    if (resultStr == null) {
      resultStr = conf.get(hive_metastoreConstants.TABLE_IS_TRANSACTIONAL.toUpperCase());
    }
    return resultStr != null && resultStr.equalsIgnoreCase("true");
  }

  public static void setTransactionalTableScan(Map<String, String> parameters, boolean isAcidTable) {
    parameters.put(ConfVars.HIVE_TRANSACTIONAL_TABLE_SCAN.varname, Boolean.toString(isAcidTable));
  }

  public static void setTransactionalTableScan(Configuration conf, boolean isAcidTable) {
    HiveConf.setBoolVar(conf, ConfVars.HIVE_TRANSACTIONAL_TABLE_SCAN, isAcidTable);
  }

  /** Checks if a table is a valid ACID table.
   * Note, users are responsible for using the correct TxnManager. We do not look at
   * SessionState.get().getTxnMgr().supportsAcid() here
   * @param table table
   * @return true if table is a legit ACID table, false otherwise
   */
  public static boolean isAcidTable(Table table) {
    if (table == null) {
      return false;
    }
    String tableIsTransactional = table.getProperty(hive_metastoreConstants.TABLE_IS_TRANSACTIONAL);
    if (tableIsTransactional == null) {
      tableIsTransactional = table.getProperty(hive_metastoreConstants.TABLE_IS_TRANSACTIONAL.toUpperCase());
    }

    return tableIsTransactional != null && tableIsTransactional.equalsIgnoreCase("true");
  }
}
