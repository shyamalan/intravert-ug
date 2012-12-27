package org.usergrid.vx.experimental;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.KSMetaData;
import org.apache.cassandra.db.ColumnFamily;
import org.apache.cassandra.db.ConsistencyLevel;
import org.apache.cassandra.db.DecoratedKey;
import org.apache.cassandra.db.IColumn;
import org.apache.cassandra.db.ReadCommand;
import org.apache.cassandra.db.Row;
import org.apache.cassandra.db.RowMutation;
import org.apache.cassandra.db.SliceByNamesReadCommand;

import org.apache.cassandra.db.SliceFromReadCommand;
import org.apache.cassandra.db.filter.ColumnSlice;
import org.apache.cassandra.db.filter.QueryPath;
import org.apache.cassandra.dht.IPartitioner;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.exceptions.IsBootstrappingException;
import org.apache.cassandra.exceptions.OverloadedException;
import org.apache.cassandra.exceptions.ReadTimeoutException;
import org.apache.cassandra.exceptions.UnavailableException;
import org.apache.cassandra.exceptions.WriteTimeoutException;
import org.apache.cassandra.service.MigrationManager;
import org.apache.cassandra.service.StorageProxy;
import org.apache.cassandra.service.StorageService;
import org.apache.cassandra.thrift.CassandraServer;
import org.apache.cassandra.thrift.CfDef;
import org.apache.cassandra.thrift.ColumnParent;
import org.apache.cassandra.thrift.ColumnPath;
import org.apache.cassandra.thrift.KsDef;
import org.apache.cassandra.utils.ByteBufferUtil;

public class IntraService {


	public IntraRes handleIntraReq(IntraReq req,IntraRes res){
		IntraState state = new IntraState();
		if ( verifyReq(req,res) == false ){
			return res;
		} else {
			executeReq(req, res, state);
		}		
		return res;
	}
	/* Process a request, return a response.  Trap errors
	 * and set the res object appropriately. Try not to do much heavy lifting here
	 * delegate complex processing to methods.*/
	protected boolean executeReq(IntraReq req, IntraRes res, IntraState state) {
		for (int i =0;i<req.getE().size();i++){
			IntraOp op = req.getE().get(i);
			if (op.getType().equals("setkeyspace")) {
				state.currentKeyspace = (String) op.getOp().get("keyspace");
				res.getOpsRes().put(i, "OK");
			} else if (op.getType().equals("createkeyspace")) {
				createKeyspace(req,res,state, i);
			} else if (op.getType().equals("createcolumnfamily")) {
				createColumnFamily(req,res,state, i);
			} else if (op.getType().equals("setcolumnfamily")){
				state.currentColumnFamily = (String) op.getOp().get("columnfamily");
				res.getOpsRes().put(i, "OK");
			} else if (op.getType().equals("autotimestamp")){
				state.autoTimestamp = true;
				res.getOpsRes().put(i, "OK");
			} else if (op.getType().equals("set")){
				set(req,res,state,i);
			} else if (op.getType().equals("slice")){
				slice(req,res,state,i);
			} else if (op.getType().equals("get")){
				List<Map> finalResults = new ArrayList<Map>();
				ByteBuffer rowkey = byteBufferForObject(resolveObject(op.getOp().get("rowkey"),req,res,state,i));
				ByteBuffer column = byteBufferForObject(resolveObject(op.getOp().get("column"),req,res,state,i));
				QueryPath path = new QueryPath(state.currentColumnFamily, null);
				List<ByteBuffer> nameAsList = Arrays.asList(column);
				ReadCommand command = new SliceByNamesReadCommand(state.currentKeyspace, rowkey, path, nameAsList);
				List<Row> rows = null;
		        
	        	try {
					rows = StorageProxy.read(Arrays.asList(command), state.consistency);
					ColumnFamily cf = rows.get(0).cf;
					if (cf == null){ //cf= null is no data
					} else {
						Iterator <IColumn> it = cf.iterator();
						while (it.hasNext()){
							IColumn ic = it.next();
							HashMap m = new HashMap();
							m.put("name", ic.name());
							m.put("value", ic.value());
							finalResults.add(m);
						}
					}
					res.getOpsRes().put(i, finalResults);
				} catch (ReadTimeoutException e) {
					res.getOpsRes().put(i, e.getMessage());
				} catch (UnavailableException e) {
					res.getOpsRes().put(i, e.getMessage());
				} catch (IsBootstrappingException e) {
					res.getOpsRes().put(i, e.getMessage());
				} catch (IOException e) {
					res.getOpsRes().put(i, e.getMessage());
				}
		        
			} else if (op.getType().equals("consistency")){
				consistency(req,res,state,i);
			}
		}
		return true;
	}
	
	private Object resolveObject(Object o, IntraReq req, IntraRes res,IntraState state, int i){
		if (o instanceof String){
			return o;
		} else if (o instanceof IntraOp){
			IntraOp op = (IntraOp) o;
			if (op.getType().equals("getref")){
				Integer resultRef = (Integer) op.getOp().get("resultref");
				String wanted = (String) op.getOp().get("wanted");
				List aresult = (List) res.getOpsRes().get(resultRef);
				Map result = (Map) aresult.get(0);
				return result.get(wanted);
			} else {
				throw new RuntimeException(" do not know what to do with "+op.getType());
			}
		} else {
			throw new RuntimeException(" do not know what to do with "+o.getClass());
		}
	}
	private ByteBuffer byteBufferForObject(Object o){
		if (o instanceof String){
			return ByteBufferUtil.bytes((String) o);
		} else if (o instanceof byte[] ) { 
			return ByteBuffer.wrap((byte [])o);
		} else if (o instanceof ByteBuffer){
			return (ByteBuffer) o;
		}
		else throw new RuntimeException( "can not serializer "+o);
	}
	/*
	 * This is a top level sanity check. Make sure request have manditory parts. 
	 * If the IntraReq fails verifyReq it will not be processed at all. 
	 */
	public boolean verifyReq(IntraReq req, IntraRes res){
		if (req == null){
			res.setException("FATAL: REQUEST IS NULL");
			return false;
		}
		for (int i =0;i<req.getE().size();i++){
			IntraOp op = req.getE().get(i);
			if (op.getType()==null){
				res.setException("FATAL: op i had no type");
			} else if (op.getType().equals("setkeyspace")){
				
			} else if (op.getType().equals("setcolumnfamily")){
				
			} else if (op.getType().equals("autotimestamp")){
				
			} else if (op.getType().equals("set")){
			}
		}
		return true;
		
	}
	
	private void createColumnFamily(IntraReq req, IntraRes res, IntraState state, int i){
		IntraOp op = req.getE().get(i);
		String cf = (String) op.getOp().get("name");
		CfDef def = new CfDef();
		def.setName(cf);
		def.setKeyspace(state.currentKeyspace);
		def.unsetId();
		CFMetaData cfm = null;

		try {
			cfm = CFMetaData.fromThrift(def);
			cfm.addDefaultIndexNames();
		} catch (org.apache.cassandra.exceptions.InvalidRequestException e) {
			res.getOpsRes().put(i, e.getMessage());
			return;
		} catch (ConfigurationException e) {
			res.getOpsRes().put(i, e.getMessage());
			return;
		}
		try {
			MigrationManager.announceNewColumnFamily(cfm);
		} catch (ConfigurationException e) {
			res.getOpsRes().put(i, e.getMessage());
			return;
		}
		res.getOpsRes().put(i, "OK");
	}
	
	private void createKeyspace(IntraReq req, IntraRes res, IntraState state,
			int i) {
		Collection<CFMetaData> cfDefs = new ArrayList<CFMetaData>(0);
		IntraOp op = req.getE().get(i);
		String ks = (String) op.getOp().get("name");
		KsDef def = new KsDef();
		def.setName(ks);
		def.setStrategy_class("SimpleStrategy");
		Map<String, String> strat = new HashMap<String, String>();
		strat.put("replication_factor", "1");
		def.setStrategy_options(strat);
		KSMetaData ksm = null;
		try {
			ksm = KSMetaData.fromThrift(def,
					cfDefs.toArray(new CFMetaData[cfDefs.size()]));
		} catch (ConfigurationException e1) {
			res.getOpsRes().put(i, e1.getMessage());
			return;
		}
		try {
			MigrationManager.announceNewKeyspace(ksm);
		} catch (ConfigurationException e) {
			res.getOpsRes().put(i, e.getMessage());
			
			return;
		}
		res.getOpsRes().put(i, "OK");
	}
	
	public void set(IntraReq req, IntraRes res, IntraState state,int i) {
		IntraOp op = req.getE().get(i);
		RowMutation rm = new RowMutation(state.currentKeyspace,byteBufferForObject(
				resolveObject ( op.getOp().get("rowkey"),req,res,state, i )
				));
		QueryPath qp = new QueryPath(state.currentColumnFamily,null, byteBufferForObject(
				resolveObject ( op.getOp().get("columnName"),req,res,state, i )
				) );
		Object val = op.getOp().get("value");
		rm.add(qp, byteBufferForObject(
				resolveObject (val ,req,res,state, i )
				), (Long) (state.autoTimestamp ? state.nanotime : op.getOp().get("timestamp")));
		Collection<RowMutation> col = new ArrayList<RowMutation>();
    col.add(rm);
		try {
			StorageProxy.instance.mutate(col, state.consistency);
			res.getOpsRes().put(i, "OK");
		} catch (WriteTimeoutException e) {
			res.getOpsRes().put(i, e.getMessage());
		} catch (org.apache.cassandra.exceptions.UnavailableException e) {
			res.getOpsRes().put(i, e.getMessage());
		} catch (OverloadedException e) {
			res.getOpsRes().put(i, e.getMessage());
		}
	}
	
	private void slice(IntraReq req, IntraRes res, IntraState state,int i){
		IntraOp op = req.getE().get(i);
		List<Map> finalResults = new ArrayList<Map>();
		ByteBuffer rowkey = byteBufferForObject(resolveObject(op.getOp().get("rowkey"),req,res,state,i));
		ByteBuffer start = byteBufferForObject(resolveObject(op.getOp().get("start"),req,res,state,i));
		ByteBuffer end = byteBufferForObject(resolveObject(op.getOp().get("end"),req,res,state,i));
		List<ReadCommand> commands = new ArrayList<ReadCommand>(1);
		ColumnPath cp = new ColumnPath();
		cp.setColumn_family(state.currentColumnFamily);
		QueryPath qp = new QueryPath(cp);
		SliceFromReadCommand sr = new SliceFromReadCommand(state.currentKeyspace, rowkey, qp, start, end, false, 100);
		commands.add(sr);
		
		List<Row> results = null;
		try {
			results = StorageProxy.read(commands, state.consistency);
			ColumnFamily cf = results.get(0).cf;
			if (cf == null){ //cf= null is no data
			} else {
				Iterator <IColumn> it = cf.iterator();
				while (it.hasNext()){
					IColumn ic = it.next();
					HashMap m = new HashMap();
					m.put("name", ic.name());
					m.put("value", ic.value());
					finalResults.add(m);
				}
			}
			res.getOpsRes().put(i,finalResults);
		} catch (ReadTimeoutException e) {
			res.getOpsRes().put(i, e.getMessage());
			return;
		} catch (org.apache.cassandra.exceptions.UnavailableException e) {
			res.getOpsRes().put(i, e.getMessage());
			return;
		} catch (IsBootstrappingException e) {
			res.getOpsRes().put(i, e.getMessage());
			return;
		} catch (IOException e) {
			res.getOpsRes().put(i, e.getMessage());
			return;
		}
		res.getOpsRes().put(i, finalResults);
	}
	
	private void consistency(IntraReq req, IntraRes res, IntraState state,int i){
		IntraOp op = req.getE().get(i);
		ConsistencyLevel level = ConsistencyLevel.valueOf((String) op.getOp().get("level"));
		res.getOpsRes().put(i, "OK");
		state.consistency = level;
	}
}
