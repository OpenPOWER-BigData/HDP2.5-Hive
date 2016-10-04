package org.apache.hadoop.hive.ql.exec;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.conf.HiveConf.ConfVars;
import org.apache.hadoop.hive.ql.plan.BaseWork;
import org.apache.hadoop.hive.ql.session.SessionState;

public class GlobalWorkMapFactory {

  private ThreadLocal<Map<Path, BaseWork>> threadLocalWorkMap = null;

  private Map<Path, BaseWork> gWorkMap = null;

  public Map<Path, BaseWork> get(Configuration conf) {
    if ((SessionState.get() != null && SessionState.get().isHiveServerQuery())
        || HiveConf.getVar(conf, ConfVars.HIVE_EXECUTION_ENGINE).equals("spark")) {
      if (threadLocalWorkMap == null) {
        threadLocalWorkMap = new ThreadLocal<Map<Path, BaseWork>>() {
          @Override
          protected Map<Path, BaseWork> initialValue() {
            return new HashMap<Path, BaseWork>();
          }
        };
      }
      return threadLocalWorkMap.get();
    }

    if (gWorkMap == null) {
      gWorkMap = new HashMap<Path, BaseWork>();
    }
    return gWorkMap;
  }

}
