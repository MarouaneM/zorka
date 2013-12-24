/**
 * Copyright 2012-2013 Rafal Lewczuk <rafal.lewczuk@jitlogic.com>
 * <p/>
 * This is free software. You can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 * <p/>
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * <p/>
 * You should have received a copy of the GNU General Public License
 * along with this software. If not, see <http://www.gnu.org/licenses/>.
 */
package com.jitlogic.zico.core;


import com.jitlogic.zico.core.eql.Parser;
import com.jitlogic.zico.core.model.HostInfo;
import com.jitlogic.zico.core.model.KeyValuePair;
import com.jitlogic.zico.core.model.SymbolicExceptionInfo;
import com.jitlogic.zico.core.model.TraceInfo;
import com.jitlogic.zico.core.model.TraceInfoRecord;
import com.jitlogic.zico.core.model.TraceInfoSearchQuery;
import com.jitlogic.zico.core.model.TraceInfoSearchResult;
import com.jitlogic.zico.core.model.TraceRecordSearchQuery;
import com.jitlogic.zico.core.rds.RDSCleanupListener;
import com.jitlogic.zico.core.rds.RDSStore;
import com.jitlogic.zico.core.search.EqlTraceRecordMatcher;
import com.jitlogic.zico.core.search.FullTextTraceRecordMatcher;
import com.jitlogic.zico.core.search.TraceRecordMatcher;
import com.jitlogic.zico.shared.data.TraceInfoSearchQueryProxy;
import com.jitlogic.zorka.common.tracedata.FressianTraceFormat;
import com.jitlogic.zorka.common.tracedata.SymbolRegistry;
import com.jitlogic.zorka.common.tracedata.SymbolicException;
import com.jitlogic.zorka.common.tracedata.SymbolicStackElement;
import com.jitlogic.zorka.common.tracedata.TraceMarker;
import com.jitlogic.zorka.common.tracedata.TraceRecord;
import com.jitlogic.zorka.common.util.ZorkaUtil;
import org.fressian.FressianWriter;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentNavigableMap;

/**
 * Represents performance data store for a single agent.
 */
public class HostStore implements Closeable, RDSCleanupListener {

    private final static Logger log = LoggerFactory.getLogger(HostStore.class);

    public final static String HOST_PROPERTIES = "host.properties";

    private static final String PROP_ADDR = "addr";
    private static final String PROP_PASS = "pass";
    private static final String PROP_FLAGS = "flags";
    private static final String PROP_SIZE = "size";

    private SymbolRegistry symbolRegistry;

    private String rootPath;

    private TraceRecordStore traceDataStore;
    private TraceRecordStore traceIndexStore;

    private TraceTemplateManager templater;  // TODO move this out to service object

    private ZicoConfig config;


    private DB db;
    private ConcurrentNavigableMap<Long,TraceInfoRecord> infos;
    private Map<Integer,String> tids;

    private boolean needsCleanup = false;

    private String name;
    private String addr = "";
    private String pass = "";
    private int flags;
    private long maxSize;


    public HostStore(ZicoConfig config, TraceTemplateManager templater, String name)  {

        this.name = name;
        this.config = config;

        this.rootPath = ZorkaUtil.path(config.getDataDir(), ZicoUtil.safePath(name));

        File hostDir = new File(rootPath);

        if (!hostDir.exists()) {
            this.maxSize = config.longCfg("rds.max.size", 1024L*1024L*1024L);
            hostDir.mkdirs();
            save();
        } else {
            load();
        }

        this.templater = templater;

        open();
    }


    public void open() {
        try {
            this.symbolRegistry = new PersistentSymbolRegistry(
                    new File(ZicoUtil.ensureDir(rootPath), "symbools.dat"));
            traceDataStore = new TraceRecordStore(config, this, "tdat");
            traceIndexStore = new TraceRecordStore(config, this, "tidx");
            db = DBMaker.newFileDB(new File(rootPath, "traces.db"))
                    .asyncWriteDisable().asyncFlushDelay(100)
                    .closeOnJvmShutdown().make();
            infos = db.getTreeMap("INFOS");
            tids = db.getTreeMap("TIDS");
        } catch (IOException e) {
            log.error("Cannot open host store " + name, e);
        }
    }


    public int countTraces() {
        return infos.size();
    }


    public synchronized void processTraceRecord(TraceRecord rec) throws IOException {
        TraceRecordStore.ChunkInfo dchunk = traceDataStore.write(rec);

        if (needsCleanup) {
            // TODO clean up infos table
            // TODO clean up index RDS storage
        }

        ByteArrayOutputStream os = new ByteArrayOutputStream();
        FressianWriter writer = new FressianWriter(os, FressianTraceFormat.WRITE_LOOKUP);

        List<TraceRecord> tmp = rec.getChildren();
        rec.setChildren(null);
        writer.writeObject(rec);
        rec.setChildren(tmp);

        TraceRecordStore.ChunkInfo ichunk = traceIndexStore.write(rec);

        TraceInfoRecord tir = new TraceInfoRecord(rec,
                dchunk.getOffset(), dchunk.getLength(),
                ichunk.getOffset(), ichunk.getLength());

        infos.put(tir.getDataOffs(), tir);

        int traceId = tir.getTraceId();

        if (!tids.containsKey(traceId)) {
            tids.put(traceId, symbolRegistry.symbolName(traceId));
        }

    }


    public synchronized void commit() {
        if (db != null) {
            long t1 = System.nanoTime();
            db.commit();
            long t = (System.nanoTime() - t1) / 1000000L;
            log.debug(name + ": Commit took " + t + " ms");
        }
    }


    public synchronized TraceInfoSearchResult search(TraceInfoSearchQuery query) throws IOException {

        List<TraceInfo> lst = new ArrayList<>(query.getLimit());

        TraceInfoSearchResult result = new TraceInfoSearchResult(query.getSeq(), lst);

        TraceRecordMatcher matcher = null;

        int traceId = query.getTraceName() != null ? symbolRegistry.symbolId(query.getTraceName()) : 0;

        if (query.getSearchExpr() != null) {
            if (query.hasFlag(TraceInfoSearchQueryProxy.EQL_QUERY)) {
                matcher = new EqlTraceRecordMatcher(symbolRegistry,
                        Parser.expr(query.getSearchExpr()),
                        0, 0);
            } else {
                matcher = new FullTextTraceRecordMatcher(symbolRegistry,
                        TraceRecordSearchQuery.SEARCH_ALL, query.getSearchExpr());
            }
        }

        // TODO implement query execution time limit

        int serarchFlags = query.getFlags();
        boolean asc = 0 == (serarchFlags & TraceInfoSearchQueryProxy.ORDER_DESC);

        for (Long key = asc ? infos.higherKey(query.getOffset()-1) : infos.lowerKey(query.getOffset()+1);
             key != null;
             key = asc ? infos.higherKey(key) : infos.lowerKey(key)) {

            TraceInfoRecord tir = infos.get(key);

            if (query.hasFlag(TraceInfoSearchQueryProxy.ERRORS_ONLY) && 0 == (tir.getTflags() & TraceMarker.ERROR_MARK)) {
                continue;
            }

            if (traceId != 0 && tir.getTraceId() != traceId) {
                continue;
            }

            if (tir.getDuration() < query.getMinMethodTime()) {
                continue;
            }

            TraceRecord idxtr = (query.hasFlag(TraceInfoSearchQueryProxy.DEEP_SEARCH) && matcher != null)
                  ? traceDataStore.read(tir.getDataChunk())
                  : traceIndexStore.read(tir.getIndexChunk());

            if (idxtr != null) {
                if (matcher instanceof EqlTraceRecordMatcher) {
                    ((EqlTraceRecordMatcher)matcher).setTotalTime(tir.getDuration());
                }
                if (matcher == null || recursiveMatch(matcher, idxtr)) {
                    lst.add(toTraceInfo(tir, idxtr));
                    result.setLastOffs(asc ? tir.getDataOffs()+1 : tir.getDataOffs()-1);
                    if (lst.size() == query.getLimit()) {
                        result.markFlag(TraceInfoSearchResult.MORE_RESULTS);
                        return result;
                    }
                }
            }
        }

        return result;
    }


    private boolean recursiveMatch(TraceRecordMatcher matcher, TraceRecord tr) {
        if (matcher.match(tr)) {
            return true;
        }

        if (tr.numChildren() > 0) {
            for (TraceRecord c : tr.getChildren()) {
                if (matcher.match(c)) {
                    return true;
                }
            }
        }

        return false;
    }


    public TraceInfoRecord getInfoRecord(long offs) {
        return infos.get(offs);
    }


    private TraceInfo toTraceInfo(TraceInfoRecord itr, TraceRecord tr) throws IOException {

        if (tr == null) {
            tr = traceIndexStore.read(itr.getIndexChunk());
        }

        TraceInfo ti = new TraceInfo();
        ti.setHostName(name);
        ti.setDataOffs(itr.getDataOffs());
        ti.setTraceId(itr.getTraceId());
        ti.setTraceType(symbolRegistry.symbolName(itr.getTraceId()));
        ti.setMethodFlags(itr.getRflags());
        ti.setTraceFlags(itr.getTflags());
        ti.setClassId(tr.getClassId());
        ti.setMethodId(tr.getMethodId());
        ti.setSignatureId(tr.getSignatureId());
        ti.setStatus(tr.getException() != null                 // TODO get rid of this field
                || tr.hasFlag(TraceRecord.EXCEPTION_PASS)
                || tr.getMarker().hasFlag(TraceMarker.ERROR_MARK) ? 1 : 0);
        ti.setRecords(itr.getRecords());
        ti.setDataLen(itr.getDataLen());
        ti.setCalls(itr.getCalls());
        ti.setErrors(itr.getErrors());
        ti.setExecutionTime(itr.getDuration());
        ti.setClock(tr.getMarker().getClock());

        if (tr != null && tr.getAttrs() != null) {
            List<KeyValuePair> keyvals = new ArrayList<KeyValuePair>(tr.getAttrs().size());
            for (Map.Entry<Integer,Object> e : tr.getAttrs().entrySet()) {
                keyvals.add(new KeyValuePair(symbolRegistry.symbolName(e.getKey()), "" + e.getValue()));
            }
            ti.setAttributes(ZicoUtil.sortKeyVals(keyvals));
        } else {
            ti.setAttributes(Collections.EMPTY_LIST);
        }

        if (tr.getException() != null) {
            SymbolicExceptionInfo sei = new SymbolicExceptionInfo();
            SymbolicException sex = (SymbolicException)tr.getException();
            sei.setExClass(symbolRegistry.symbolName(sex.getClassId()));
            sei.setMessage(sex.getMessage());
            List<String> lst = new ArrayList<String>();

            for (SymbolicStackElement sel : sex.getStackTrace()) {
                lst.add(symbolRegistry.symbolName(sel.getClassId()) + ":" + sel.getLineNum());
                if (lst.size() > 10) {
                    break;
                }
            }

            sei.setStackTrace(lst);

            ti.setExceptionInfo(sei);
        }

        ti.setDescription(templater.templateDescription(symbolRegistry, ti));

        return ti;
    }

    public Map<Integer,String> getTidMap() {
        return Collections.unmodifiableMap(tids);
    }


    public String toString() {
        return "HostStore(" + name + ")";
    }



    public synchronized void load() {
        File f = new File(rootPath, HOST_PROPERTIES);
        Properties props = new Properties();
        if (f.exists() && f.canRead()) {
            try (InputStream is = new FileInputStream(f)) {
                props.load(is);
            } catch (IOException e) {
                log.error("Cannot read " + f, e);
            }
        }

        this.addr = props.getProperty(PROP_ADDR, "127.0.0.1");
        this.pass = props.getProperty(PROP_PASS, "");
        this.flags = Integer.parseInt(props.getProperty(PROP_FLAGS, "0"));
        this.maxSize = Integer.parseInt(props.getProperty(PROP_SIZE,
            ""+config.kiloCfg("rds.max.size", 1024 * 1024 * 1024L)));

    }


    public synchronized void save() {

        Properties props = new Properties();
        props.setProperty(PROP_ADDR, addr);
        props.setProperty(PROP_PASS, pass);
        props.setProperty(PROP_FLAGS, ""+flags);
        props.setProperty(PROP_SIZE, ""+maxSize);

        File f = new File(rootPath, HOST_PROPERTIES);

        try (OutputStream os = new FileOutputStream(f)) {
            props.store(os, "ZICO Host Descriptor");
        } catch (IOException e) {
            log.error("Cannot write " + f, e);
        }

// TODO automatic cleanup after (potential) shrinking
//        if (rdsData != null) {
//            rdsData.setMaxSize(maxSize);
//            try {
//                rdsData.cleanup();
//            } catch (IOException e) {
//                log.error("Error resizing RDS store for " + getName());
//            }
//        }
    }


    @Override
    public synchronized void close() throws IOException {
        traceDataStore.close();
        traceIndexStore.close();
        db.commit();
        db.close();
    }


    public TraceRecordStore getTraceDataStore() {
        return traceDataStore;
    }

    public TraceRecordStore getTraceIndexStore() {
        return traceIndexStore;
    }

    public SymbolRegistry getSymbolRegistry() {
        return symbolRegistry;
    }


    public String getRootPath() {
        return rootPath;
    }


    @Override
    public void onChunkRemoved(Long start, Long length) {
        needsCleanup = true;
    }


    @Override
    public void onChunkStarted(RDSStore origin, Long start) {
        if (traceIndexStore != null) {
            try {
                traceIndexStore.getRds().rotate();
            } catch (IOException e) {
                log.error("Cannot rotate index for " + name, e);
            }
        }
    }

    public String getName() {
        return name;
    }

    public String getAddr() {
        return addr;
    }

    public void setAddr(String addr) {
        this.addr = addr;
        save();
    }

    public String getPass() {
        return pass;
    }

    public void setPass(String pass) {
        this.pass = pass;
        save();
    }

    public int getFlags() {
        return flags;
    }

    public void setFlags(int flags) {
        this.flags = flags;
        save();
    }

    public boolean hasFlag(int flag) {
        return 0 != (this.flags & flag);
    }

    public long getMaxSize() {
        return maxSize;
    }

    public void setMaxSize(long maxSize) {
        this.maxSize = maxSize;
        save();
    }

    public boolean isEnabled() {
        return 0 == (flags & HostInfo.DISABLED);
    }

    public void setEnabled(boolean enabled) {
        if (enabled) {
            flags &= ~HostInfo.DISABLED;
        } else {
            flags |= HostInfo.DISABLED;
        }
        save();
    }
}

