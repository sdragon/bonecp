/*

Copyright 2009 Wallace Wadge

This file is part of BoneCP.

BoneCP is free software: you can redistribute it and/or modify
it under the terms of the GNU Lesser General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

BoneCP is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with BoneCP.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.jolbox.bonecp;

import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



/**
 * Connection pool (main class).
 * @author wwadge
 *
 */
public class BoneCP implements BoneCPMBean {
	/** Exception message. */
	private static final String UNCLOSED_EXCEPTION_MESSAGE = "Connection obtained from thread [%s] was never closed. \nStack trace of location where connection was obtained follows:\n";
	/** JMX constant. */
	public static final String MBEAN_CONFIG = "com.jolbox.bonecp:type=BoneCPConfig";
	/** JMX constant. */
	public static final String MBEAN_BONECP = "com.jolbox.bonecp:type=BoneCP";
	/** Constant for keep-alive test */
	private static final String[] METADATATABLE = new String[] {"TABLE"};
	/** Constant for keep-alive test */
	private static final String KEEPALIVEMETADATA = "BONECPKEEPALIVE";
	/** Create more threads when we hit x% of our possible number of connections. */
	protected static final int HIT_THRESHOLD = 20;
	/** Number of partitions passed in constructor. **/
	private int partitionCount;
	/** Partitions handle. */
	private ConnectionPartition[] partitions;
	/** Handle to factory that creates 1 thread per partition that periodically wakes up and performs some
	 * activity on the connection.
	 */
	private ScheduledExecutorService keepAliveScheduler;
	/** Executor for threads watching each partition to dynamically create new threads/kill off excess ones.
	 */
	private ExecutorService connectionsScheduler;
	/** Configuration object used in constructor. */
	private BoneCPConfig config;
	/** almost full locking. **/
	final Lock connectionsObtainedLock = new ReentrantLock();
	/** If set to true, we have run out of connections at some point. */
	private volatile boolean connectionStarvationTriggered = false;
	/** If set to true, config has specified the use of helper threads. */
	private boolean releaseHelperThreadsConfigured;
	/** pointer to the thread containing the release helper threads. */
	private ExecutorService releaseHelper;
	/** Executor service for obtaining a connection in an asynchronous fashion. */
	private ExecutorService asyncExecutor;
	/** Logger class. */
	private static Logger logger = LoggerFactory.getLogger(BoneCP.class);
	/** JMX support. */
	private MBeanServer mbs; 
	/** Prevent repeated termination of all connections when the DB goes down. */
	protected Lock terminationLock = new ReentrantLock();
	/** If set to true, create a new thread that monitors a connection and displays warnings if application failed to 
	 * close the connection.
	 */
	private boolean closeConnectionWatch = false;
	/** Threads monitoring for bad connection requests. */
	private ExecutorService closeConnectionExecutor;


	/**
	 * Closes off this connection pool.
	 */
	public void shutdown(){
		this.keepAliveScheduler.shutdownNow(); // stop threads from firing.
		this.connectionsScheduler.shutdownNow(); // stop threads from firing.

		terminateAllConnections();
	}

	/** Just a synonym to shutdown. */
	public void close(){
		shutdown();
	}

	/** Closes off all connections in all partitions. */
	protected void terminateAllConnections(){
		if (this.terminationLock.tryLock()){
			try{
				// close off all connections.
				for (int i=0; i < this.partitionCount; i++) {
					ConnectionHandle conn;
					while ((conn = this.partitions[i].getFreeConnections().poll()) != null){
						postDestroyConnection(conn);

						try {
							conn.internalClose();
						} catch (SQLException e) {
							logger.error("Error in attempting to close connection", e);
						}
					}
				}
			} finally {
				this.terminationLock.unlock();
			}
		}
	}

	/** Update counters and call hooks.
	 * @param handle connection handle.
	 */
	protected void postDestroyConnection(ConnectionHandle handle){
		ConnectionPartition partition = handle.getOriginatingPartition();
		partition.updateCreatedConnections(-1);
		partition.setUnableToCreateMoreTransactions(false); // we can create new ones now

		// "Destroying" for us means: don't put it back in the pool.
		if (handle.getConnectionHook() != null){
			handle.getConnectionHook().onDestroy(handle);
		}

	}
	/**
	 * Constructor.
	 * @param config Configuration for pool
	 * @throws SQLException on error
	 */
	public BoneCP(BoneCPConfig config) throws SQLException {

		config.sanitize();
		this.asyncExecutor = Executors.newCachedThreadPool();
		this.releaseHelperThreadsConfigured = config.getReleaseHelperThreads() > 0;
		this.config = config;
		this.partitions = new ConnectionPartition[config.getPartitionCount()];
		this.keepAliveScheduler =  Executors.newScheduledThreadPool(config.getPartitionCount(), new CustomThreadFactory("BoneCP-keep-alive-scheduler", true));
		this.connectionsScheduler =  Executors.newFixedThreadPool(config.getPartitionCount(), new CustomThreadFactory("BoneCP-pool-watch-thread", true));
		this.partitionCount = config.getPartitionCount();
		this.closeConnectionWatch = config.isCloseConnectionWatch();

		if (this.closeConnectionWatch){
			logger.warn("Thread close connection monitoring has been enabled. This will negatively impact on your performance. Only enable this option for debugging purposes!");
			this.closeConnectionExecutor =  Executors.newCachedThreadPool(new CustomThreadFactory("BoneCP-connection-watch-thread", true));

		}
		for (int p=0; p < config.getPartitionCount(); p++){
			ConnectionPartition connectionPartition = new ConnectionPartition(this);
			final Runnable connectionTester = new ConnectionTesterThread(connectionPartition, this.keepAliveScheduler, this);
			this.partitions[p]=connectionPartition;
			this.partitions[p].setFreeConnections(new ArrayBlockingQueue<ConnectionHandle>(config.getMaxConnectionsPerPartition()));

			for (int i=0; i < config.getMinConnectionsPerPartition(); i++){
				this.partitions[p].addFreeConnection(new ConnectionHandle(config.getJdbcUrl(), config.getUsername(), config.getPassword(), this));
			}

			if (config.getIdleConnectionTestPeriod() > 0){
				this.keepAliveScheduler.scheduleAtFixedRate(connectionTester, config.getIdleConnectionTestPeriod(), config.getIdleConnectionTestPeriod(), TimeUnit.MILLISECONDS);
			}

			// watch this partition for low no of threads
			this.connectionsScheduler.execute(new PoolWatchThread(connectionPartition, this));
		}

		initJMX(); 
	}

	/**
	 * Initialises JMX stuff.  
	 */
	protected void initJMX() {
		if (this.mbs == null){ // this way makes it easier for mocking.
			this.mbs = ManagementFactory.getPlatformMBeanServer();
		}
		try {
			ObjectName name = new ObjectName(MBEAN_BONECP);
			ObjectName configname = new ObjectName(MBEAN_CONFIG);

			if (!this.mbs.isRegistered(name)){
				this.mbs.registerMBean(this, name); 
			}
			if (!this.mbs.isRegistered(configname)){
				this.mbs.registerMBean(this.config, configname); 
			}
		} catch (Exception e) {
			logger.error("Unable to start JMX", e);
		}
	}



	/**
	 * Returns a free connection.
	 * @return Connection handle.
	 * @throws SQLException 
	 */
	public Connection getConnection() throws SQLException {
		int partition = (int) (Thread.currentThread().getId() % this.partitionCount);

		ConnectionPartition connectionPartition = this.partitions[partition];
		if (!connectionPartition.isUnableToCreateMoreTransactions()){
			maybeSignalForMoreConnections(connectionPartition);
		}

		ConnectionHandle result;
		if (this.connectionStarvationTriggered) {
			try{
				result = connectionPartition.getFreeConnections().take();
			}
			catch (InterruptedException e) {
				throw new SQLException(e);
			}
		} else { 
			result = connectionPartition.getFreeConnections().poll();
		}


		if (result == null) { 

			// we ran out of space on this partition, pick another free one
			for (int i=0; i < this.partitionCount ; i++){
				if (i == partition) {
					continue; // we already determined it's not here
				}
				result = this.partitions[i].getFreeConnections().poll();
				connectionPartition = this.partitions[i];
				if (result != null) {
					break;
				}
			}
		}

		// we still didn't find an empty one, wait forever until our partition is free
		if (result == null) {
			try {
				this.connectionStarvationTriggered   = true; 
				result = connectionPartition.getFreeConnections().take();
			}
			catch (InterruptedException e) {
				throw new SQLException(e);
			}
		}
		result.setOriginatingPartition(connectionPartition);
		result.renewConnection();

		// Give an application the chance to do something with it.
		if (result.getConnectionHook() != null){
			result.getConnectionHook().onCheckOut(result);
		}

		if (this.closeConnectionWatch){ // a debugging tool
			watchConnection(result);
		}
		return result;
	}


	/** Starts off a new thread to monitor this connection attempt.
	 * @param connectionHandle to monitor 
	 */
	private void watchConnection(ConnectionHandle connectionHandle) {
		String message = captureStackTrace(UNCLOSED_EXCEPTION_MESSAGE);
		this.closeConnectionExecutor.submit(new CloseThreadMonitor(Thread.currentThread(), connectionHandle, message));
	}

	/** Throw an exception to capture it so as to be able to print it out later on
	 * @param message message to display
	 * @return Stack trace message
	 * 
	 */
	protected String captureStackTrace(String message) {
		StringBuffer stringBuffer = new StringBuffer(String.format(message, Thread.currentThread().getName()));		
			StackTraceElement[] trace = Thread.currentThread().getStackTrace();
			for(int i = 0; i < trace.length; i++){
				stringBuffer.append(" "+trace[i]+"\r\n");
			}

			stringBuffer.append("");

		return stringBuffer.toString();
	}

	/** Obtain a connection asynchronously by queuing a request to obtain a connection in a separate thread. 
	 * 
	 *  Use as follows:<p>
	 *      Future&lt;Connection&gt; result = pool.getAsyncConnection();<p>
	 *       ... do something else in your application here ...<p>
	 *      Connection connection = result.get(); // get the connection<p>
	 *      
	 * @return A Future task returning a connection. 
	 */
	public Future<Connection> getAsyncConnection(){

		return this.asyncExecutor.submit(new Callable<Connection>() {

			@Override
			public Connection call() throws Exception {
				return getConnection();
			}});
	}
	/**
	 * Tests if this partition has hit a threshold and signal to the pool watch thread to create new connections
	 * @param connectionPartition to test for.
	 */
	private void maybeSignalForMoreConnections(ConnectionPartition connectionPartition) {

		if (!connectionPartition.isUnableToCreateMoreTransactions() && connectionPartition.getFreeConnections().size()*100/connectionPartition.getMaxConnections() < HIT_THRESHOLD){
			try{
				connectionPartition.lockAlmostFullLock();
				connectionPartition.almostFullSignal();
			} finally {
				connectionPartition.unlockAlmostFullLock(); 
			}
		}


	}

	/**
	 * Releases the given connection back to the pool.
	 *
	 * @param connection to release
	 * @throws SQLException
	 */
	protected void releaseConnection(Connection connection) throws SQLException {

		try {
			ConnectionHandle handle = (ConnectionHandle)connection;

			// hook calls
			if (handle.getConnectionHook() != null){
				handle.getConnectionHook().onCheckIn(handle);
			}

			// release immediately or place it in a queue so that another thread will eventually close it.
			if (this.releaseHelperThreadsConfigured){
				handle.getOriginatingPartition().getConnectionsPendingRelease().put(handle);
			} else {
				internalReleaseConnection(handle);
			}
		}
		catch (InterruptedException e) {
			throw new SQLException(e);
		}
	}

	/** Release a connection by placing the connection back in the pool.
	 * @param connectionHandle Connection being released.
	 * @throws InterruptedException 
	 * @throws SQLException 
	 **/
	protected void internalReleaseConnection(ConnectionHandle connectionHandle) throws InterruptedException, SQLException {

		// close off tracked statements.
		connectionHandle.clearStatementHandles(false);

		if (connectionHandle.isPossiblyBroken() && !isConnectionHandleAlive(connectionHandle)){

			ConnectionPartition connectionPartition = connectionHandle.getOriginatingPartition();
			maybeSignalForMoreConnections(connectionPartition);

			postDestroyConnection(connectionHandle);
			return; // don't place back in queue - connection is broken!
		}

		connectionHandle.setConnectionLastUsed(System.currentTimeMillis());
		releaseInAnyFreePartition(connectionHandle, connectionHandle.getOriginatingPartition());
	}

	/**
	 * Releases the given connection in any available partition, starting off
	 * with the active one.
	 *
	 * @param connectionHandle to release
	 * @param activePartition Preferred partition to release this connection to
	 * @throws InterruptedException on break
	 */
	protected void releaseInAnyFreePartition(ConnectionHandle connectionHandle, ConnectionPartition activePartition) throws InterruptedException  {

		ConnectionPartition workingPartition = activePartition;
		if (!workingPartition.getFreeConnections().offer(connectionHandle)){
			// we ran out of space on this partition, pick another free one
			boolean released = false;
			for (int i=0; i < this.partitionCount; i++){
				if (this.partitions[i].getFreeConnections().offer(connectionHandle)){
					released=true;
					break;
				}
			}

			if (!released)	{
				// we still didn't find an empty one, wait forever until our partition is free
				connectionHandle.getOriginatingPartition().getFreeConnections().put(connectionHandle);
			}
		}

	}

	/** Sends a dummy statement to the server to keep the connection alive
	 * @param connection Connection handle to perform activity on
	 * @return true if test query worked, false otherwise
	 */
	public boolean isConnectionHandleAlive(ConnectionHandle connection) {
		Statement stmt = null;
		boolean result = false; 
		try {
			String testStatement = this.config.getConnectionTestStatement();
			ResultSet rs = null;

			if (testStatement == null) {
				// Make a call to fetch the metadata instead of a dummy query.
				rs = connection.getMetaData().getTables( null, null, KEEPALIVEMETADATA, METADATATABLE );
			} else {
				stmt = connection.createStatement();
				rs = stmt.executeQuery(testStatement);
			}


			if (rs != null) { 
				rs.close();
			}

			result = true;
		} catch (SQLException e) {
			// connection must be broken!
			result = false;
		} finally {
			result = closeStatement(stmt, result);
		}
		return result;
	}

	/**
	 * @param stmt
	 * @param result
	 * @return false on failure.
	 */
	private boolean closeStatement(Statement stmt, boolean result) {
		if (stmt != null) {
			try {
				stmt.close();
			} catch (SQLException e) {
				return false;
			}
		}
		return result;
	}

	/** Return total number of connections currently in use by an application
	 * @return no of leased connections
	 */
	public int getTotalLeased(){
		int total=0;
		for (int i=0; i < this.partitionCount; i++){
			total+=this.partitions[i].getCreatedConnections()-this.partitions[i].getFreeConnections().size();
		}
		return total;
	}

	/** Return the number of free connections available to an application right away (excluding connections that can be
	 * created dynamically)
	 * @return number of free connections
	 */
	public int getTotalFree(){
		int total=0;
		for (int i=0; i < this.partitionCount; i++){
			total+=this.partitions[i].getFreeConnections().size();
		}
		return total;
	}

	/**
	 * Return total number of connections created in all partitions.
	 *
	 * @return number of created connections
	 */
	public int getTotalCreatedConnections(){
		int total=0;
		for (int i=0; i < this.partitionCount; i++){
			total+=this.partitions[i].getCreatedConnections();
		}
		return total;
	}


	/**
	 * Gets config object.
	 *
	 * @return config object 
	 */
	public BoneCPConfig getConfig() {
		return this.config;
	}

	/**
	 * @return the releaseHelper
	 */
	protected ExecutorService getReleaseHelper() {
		return this.releaseHelper;
	}

	/**
	 * @param releaseHelper the releaseHelper to set
	 */
	protected void setReleaseHelper(ExecutorService releaseHelper) {
		this.releaseHelper = releaseHelper;
	}

}
