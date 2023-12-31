/******************************************************************************
 * Product: Adempiere ERP & CRM Smart Business Solution                        *
 * Copyright (C) 1999-2006 ComPiere, Inc. All Rights Reserved.                *
 * This program is free software; you can redistribute it and/or modify it    *
 * under the terms version 2 of the GNU General Public License as published   *
 * by the Free Software Foundation. This program is distributed in the hope   *
 * that it will be useful, but WITHOUT ANY WARRANTY; without even the implied *
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.           *
 * See the GNU General Public License for more details.                       *
 * You should have received a copy of the GNU General Public License along    *
 * with this program; if not, write to the Free Software Foundation, Inc.,    *
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA.                     *
 * For the text or an alternative of this public license, you may reach us    *
 * ComPiere, Inc., 2620 Augustine Dr. #245, Santa Clara, CA 95054, USA        *
 * or via info@compiere.org or http://www.compiere.org/license.html           *
 *****************************************************************************/
package org.compiere.util;

import java.sql.*;
import java.util.logging.*;

import javax.sql.*;

import org.compiere.db.*;
import org.compiere.interfaces.*;

/**
 *	Adempiere Statement
 *	
 *  @author Jorg Janke
 *  @version $Id: CStatement.java,v 1.3 2006/07/30 00:54:36 jjanke Exp $
 *  ---
 *  Modifications: Handle connections properly
 *                 Close the associated connection when the statement is closed 
 *  Reason       : Due to changes brought in the connection pooling whereby the system 
 *                 no more relies upon abandoned connections.
 *  @author Ashley Ramdass (Posterita)
 */
public class CStatement implements Statement
{
	protected boolean useTransactionConnection = false;
	
	private boolean close = false;


	/**
	 *	Prepared Statement Constructor
	 *
	 *  @param resultSetType - ResultSet.TYPE_FORWARD_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.TYPE_SCROLL_SENSITIVE
	 *  @param resultSetConcurrency - ResultSet.CONCUR_READ_ONLY or ResultSet.CONCUR_UPDATABLE
	 * 	@param trxName transaction name or null
	 */
	public CStatement (int resultSetType, int resultSetConcurrency,
		String trxName)
	{
		p_vo = new CStatementVO (resultSetType, resultSetConcurrency);
		p_vo.setTrxName(trxName);

		init();
	}	//	CPreparedStatement

	/**
	 * Initialise the statement wrapper object 
	 */
	protected void init()
	{
		//Local access
		if (!DB.isRemoteObjects())
		{
			try
			{
				Connection conn = null;
				Trx trx = p_vo.getTrxName() == null ? null : Trx.get(p_vo.getTrxName(), true);
				if (trx != null)
				{
					conn = trx.getConnection();
					useTransactionConnection = true;
				}
				else
				{
					if (p_vo.getResultSetConcurrency() == ResultSet.CONCUR_UPDATABLE)
						conn = DB.getConnectionRW ();
					else
						conn = DB.getConnectionRO();
				}
				if (conn == null)
					throw new DBException("No Connection");
				p_stmt = conn.createStatement(p_vo.getResultSetType(), p_vo.getResultSetConcurrency());
				return;
			}
			catch (SQLException e)
			{
				log.log(Level.SEVERE, "CStatement", e);
			}
		}
	}
	
	/**
	 * 	Minimum Constructor for sub classes
	 */
	protected CStatement()
	{
		super();
	}	//	CStatement

	/**
	 * 	Remote Constructor
	 *	@param vo value object
	 */
	public CStatement (CStatementVO vo)
	{
		p_vo = vo;
		init();
	}	//	CPreparedStatement


	/**	Logger							*/
	protected transient CLogger			log = CLogger.getCLogger (getClass());
	/** Used if local					*/
	protected transient Statement		p_stmt = null;
	/**	Value Object					*/
	protected CStatementVO				p_vo = null;
	/** Remote Errors					*/
	protected int						p_remoteErrors = 0;
	/** Object to hold remote execute result **/
	protected ExecuteResult executeResult;


	/**
	 * 	Execute Query
	 * 	@param sql0 unconverted SQL to execute
	 * 	@return ResultSet or RowSet
	 * 	@throws SQLException
	 * @see java.sql.Statement#executeQuery(String)
	 */
	public ResultSet executeQuery (String sql0) throws SQLException
	{
		//	Convert SQL
		p_vo.setSql(DB.getDatabase().convertStatement(sql0));
		if (p_stmt != null)	//	local
			return p_stmt.executeQuery(p_vo.getSql());
			
		//	Client -> remote sever
		log.finest("server => " + p_vo + ", Remote=" + DB.isRemoteObjects());
		try
		{
			boolean remote = DB.isRemoteObjects() && CConnection.get().isAppsServerOK(false);
			if (remote && p_remoteErrors > 1)
				remote = CConnection.get().isAppsServerOK(true);
			if (remote)
			{
				Server server = CConnection.get().getServer();
				if (server != null)
				{
					ResultSet rs = server.stmt_getRowSet (p_vo, SecurityToken.getInstance());
					if (rs == null)
						log.warning("ResultSet is null - " + p_vo);
					else
						p_remoteErrors = 0;
					return rs;
				}
				log.log(Level.SEVERE, "AppsServer not found");
				p_remoteErrors++;
			}
		}
		catch (Exception ex)
		{
			log.log(Level.SEVERE, "AppsServer error", ex);
			p_remoteErrors++;
			if (ex instanceof SQLException)
				throw (SQLException)ex;
			else if (ex instanceof RuntimeException)
				throw (RuntimeException)ex;
			else
				throw new RuntimeException(ex);
		}
		throw new IllegalStateException("Remote Connection - Application server not available");
	}	//	executeQuery


	/**
	 * 	Execute Update
	 *	@param sql0 unconverted sql
	 *	@return no of updated rows
	 *	@throws SQLException
	 * @see java.sql.Statement#executeUpdate(String)
	 */
	public int executeUpdate (String sql0) throws SQLException
	{
		//	Convert SQL
		p_vo.setSql(DB.getDatabase().convertStatement(sql0));
		if (p_stmt != null)	//	local
			return p_stmt.executeUpdate (p_vo.getSql());

		//	Client -> remote sever
		log.finest("server => " + p_vo + ", Remote=" + DB.isRemoteObjects());
		try
		{
			boolean remote = DB.isRemoteObjects() && CConnection.get().isAppsServerOK(false);
			if (remote && p_remoteErrors > 1)
				remote = CConnection.get().isAppsServerOK(true);
			if (remote)
			{
				Server server = CConnection.get().getServer();
				if (server != null)
				{
					int result = server.stmt_executeUpdate(p_vo, SecurityToken.getInstance());
					p_vo.clearParameters();		//	re-use of result set
					return result;
				}
				log.log(Level.SEVERE, "AppsServer not found");
				p_remoteErrors++;
			}
		}
		catch (Exception ex)
		{
			log.log(Level.SEVERE, "AppsServer error", ex);
			p_remoteErrors++;
			if (ex instanceof SQLException)
				throw (SQLException)ex;
			else if (ex instanceof RuntimeException)
				throw (RuntimeException)ex;
			else
				throw new RuntimeException(ex);
		}
		throw new IllegalStateException("Remote Connection - Application server not available");
	}	//	executeUpdate

	/**
	 * 	Get Sql
	 *	@return sql
	 */
	public String getSql()
	{
		if (p_vo != null)
			return p_vo.getSql();
		return null;
	}	//	getSql


	/**
	 * 	Get Connection
	 *	@return connection for local - or null for remote
	 *	@throws SQLException
	 * @see java.sql.Statement#getConnection()
	 */
	public Connection getConnection () throws SQLException
	{
		if (p_stmt != null)
			return p_stmt.getConnection();
		return null;
	}	//	getConnection

	/**
	 * 	Commit (if local)
	 *	@throws SQLException
	 */
	public void commit() throws SQLException
	{
		Connection conn = getConnection();
		if (conn != null && !conn.getAutoCommit())
		{
			conn.commit();
			log.fine("commit");
		}
	}	//	commit


	/**
	 * Method executeUpdate
	 * @param sql0 String
	 * @param autoGeneratedKeys int
	 * @return int
	 * @throws SQLException
	 * @see java.sql.Statement#executeUpdate(String, int)
	 */
	public int executeUpdate (String sql0, int autoGeneratedKeys) throws SQLException
	{
		p_vo.setSql(DB.getDatabase().convertStatement(sql0));
		if (p_stmt != null)
			return p_stmt.executeUpdate(p_vo.getSql(), autoGeneratedKeys);
		throw new java.lang.UnsupportedOperationException ("Method executeUpdate() not yet implemented.");
	}

	/**
	 * Method executeUpdate
	 * @param sql0 String
	 * @param columnIndexes int[]
	 * @return int
	 * @throws SQLException
	 * @see java.sql.Statement#executeUpdate(String, int[])
	 */
	public int executeUpdate (String sql0, int[] columnIndexes) throws SQLException
	{
		p_vo.setSql(DB.getDatabase().convertStatement(sql0));
		if (p_stmt != null)
			return p_stmt.executeUpdate(p_vo.getSql(), columnIndexes);
		throw new java.lang.UnsupportedOperationException ("Method executeUpdate() not yet implemented.");
	}

	/**
	 * Method executeUpdate
	 * @param sql0 String
	 * @param columnNames String[]
	 * @return int
	 * @throws SQLException
	 * @see java.sql.Statement#executeUpdate(String, String[])
	 */
	public int executeUpdate (String sql0, String[] columnNames) throws SQLException
	{
		p_vo.setSql(DB.getDatabase().convertStatement(sql0));
		if (p_stmt != null)
			return p_stmt.executeUpdate(p_vo.getSql(), columnNames);
		throw new java.lang.UnsupportedOperationException ("Method executeUpdate() not yet implemented.");
	}


	/**
	 * Method execute
	 * @param sql0 String
	 * @return boolean
	 * @throws SQLException
	 * @see java.sql.Statement#execute(String)
	 */
	public boolean execute (String sql0) throws SQLException
	{
		p_vo.setSql(DB.getDatabase().convertStatement(sql0));
		if (p_stmt != null)
			return p_stmt.execute(p_vo.getSql());
		
		return remote_execute();
	}

	/**
	 * Method execute
	 * @param sql0 String
	 * @param autoGeneratedKeys int
	 * @return boolean
	 * @throws SQLException
	 * @see java.sql.Statement#execute(String, int)
	 */
	public boolean execute (String sql0, int autoGeneratedKeys) throws SQLException
	{
		p_vo.setSql(DB.getDatabase().convertStatement(sql0));
		if (p_stmt != null)
			return p_stmt.execute(p_vo.getSql(), autoGeneratedKeys);
		
		throw new java.lang.UnsupportedOperationException ("Method execute(sql, autoGeneratedKeys) not yet implemented.");
	}

	/**
	 * Method execute
	 * @param sql0 String
	 * @param columnIndexes int[]
	 * @return boolean
	 * @throws SQLException
	 * @see java.sql.Statement#execute(String, int[])
	 */
	public boolean execute (String sql0, int[] columnIndexes) throws SQLException
	{
		p_vo.setSql(DB.getDatabase().convertStatement(sql0));
		if (p_stmt != null)
			return p_stmt.execute(p_vo.getSql(), columnIndexes);
		throw new java.lang.UnsupportedOperationException ("Method execute(sql, columnIndexes) not yet implemented.");
	}

	/**
	 * Method execute
	 * @param sql0 String
	 * @param columnNames String[]
	 * @return boolean
	 * @throws SQLException
	 * @see java.sql.Statement#execute(String, String[])
	 */
	public boolean execute (String sql0, String[] columnNames) throws SQLException
	{
		p_vo.setSql(DB.getDatabase().convertStatement(sql0));
		if (p_stmt != null)
			return p_stmt.execute(p_vo.getSql(), columnNames);
		throw new java.lang.UnsupportedOperationException ("Method execute(sql, columnNames) not yet implemented.");
	}



	/**************************************************************************
	 * 	Get Max Field Size
	 * 	@return field size
	 * 	@throws SQLException
	 * @see java.sql.Statement#getMaxFieldSize()
	 */
	public int getMaxFieldSize () throws SQLException
	{
		if (p_stmt != null)
			return p_stmt.getMaxFieldSize();
		throw new java.lang.UnsupportedOperationException ("Method getMaxFieldSize() not yet implemented.");
	}

	/**
	 * Method setMaxFieldSize
	 * @param max int
	 * @throws SQLException
	 * @see java.sql.Statement#setMaxFieldSize(int)
	 */
	public void setMaxFieldSize (int max) throws SQLException
	{
		if (p_stmt != null)
			p_stmt.setMaxFieldSize(max);
		else
			throw new java.lang.UnsupportedOperationException ("Method setMaxFieldSize() not yet implemented.");
	}

	/**
	 * Method getMaxRows
	 * @return int
	 * @throws SQLException
	 * @see java.sql.Statement#getMaxRows()
	 */
	public int getMaxRows () throws SQLException
	{
		if (p_stmt != null)
			return p_stmt.getMaxRows();
		throw new java.lang.UnsupportedOperationException ("Method getMaxRows() not yet implemented.");
	}

	/**
	 * Method setMaxRows
	 * @param max int
	 * @throws SQLException
	 * @see java.sql.Statement#setMaxRows(int)
	 */
	public void setMaxRows (int max) throws SQLException
	{
		if (p_stmt != null)
			p_stmt.setMaxRows(max);
		else
			throw new java.lang.UnsupportedOperationException ("Method setMaxRows() not yet implemented.");
	}

	/*************************************************************************
	 * 	Add Batch
	 *	@param sql sql
	 *	@throws SQLException
	 * @see java.sql.Statement#addBatch(String)
	 */
	public void addBatch (String sql) throws SQLException
	{
		if (p_stmt != null)
			p_stmt.addBatch(sql);
		else
			throw new java.lang.UnsupportedOperationException ("Method addBatch() not yet implemented.");
	}

	/**
	 * Method clearBatch
	 * @throws SQLException
	 * @see java.sql.Statement#clearBatch()
	 */
	public void clearBatch () throws SQLException
	{
		if (p_stmt != null)
			p_stmt.clearBatch();
		else
			throw new java.lang.UnsupportedOperationException ("Method clearBatch() not yet implemented.");
	}

	/**
	 * Method executeBatch
	 * @return int[]
	 * @throws SQLException
	 * @see java.sql.Statement#executeBatch()
	 */
	public int[] executeBatch () throws SQLException
	{
		if (p_stmt != null)
			return p_stmt.executeBatch();
		throw new java.lang.UnsupportedOperationException ("Method executeBatch() not yet implemented.");
	}


	/**
	 * Method getMoreResults
	 * @param current int
	 * @return boolean
	 * @throws SQLException
	 * @see java.sql.Statement#getMoreResults(int)
	 */
	public boolean getMoreResults (int current) throws SQLException
	{
		if (p_stmt != null)
			return p_stmt.getMoreResults(current);
		throw new java.lang.UnsupportedOperationException ("Method getMoreResults() not yet implemented.");
	}


	/**
	 * Method getGeneratedKeys
	 * @return ResultSet
	 * @throws SQLException
	 * @see java.sql.Statement#getGeneratedKeys()
	 */
	public ResultSet getGeneratedKeys () throws SQLException
	{
		if (p_stmt != null)
			return p_stmt.getGeneratedKeys();
		throw new java.lang.UnsupportedOperationException ("Method getGeneratedKeys() not yet implemented.");
	}

	/**
	 * Method getResultSetHoldability
	 * @return int
	 * @throws SQLException
	 * @see java.sql.Statement#getResultSetHoldability()
	 */
	public int getResultSetHoldability () throws SQLException
	{
		if (p_stmt != null)
			return p_stmt.getResultSetHoldability();
		throw new java.lang.UnsupportedOperationException ("Method getResultSetHoldability() not yet implemented.");
	}

	/**
	 * Method setEscapeProcessing
	 * @param enable boolean
	 * @throws SQLException
	 * @see java.sql.Statement#setEscapeProcessing(boolean)
	 */
	public void setEscapeProcessing (boolean enable) throws SQLException
	{
		if (p_stmt != null)
			p_stmt.setEscapeProcessing(enable);
		else
			throw new java.lang.UnsupportedOperationException ("Method setEscapeProcessing() not yet implemented.");
	}

	/**
	 * Method getQueryTimeout
	 * @return int
	 * @throws SQLException
	 * @see java.sql.Statement#getQueryTimeout()
	 */
	public int getQueryTimeout () throws SQLException
	{
		if (p_stmt != null)
			return p_stmt.getQueryTimeout();
		throw new java.lang.UnsupportedOperationException ("Method getQueryTimeout() not yet implemented.");
	}

	/**
	 * Method setQueryTimeout
	 * @param seconds int
	 * @throws SQLException
	 * @see java.sql.Statement#setQueryTimeout(int)
	 */
	public void setQueryTimeout (int seconds) throws SQLException
	{
		if (p_stmt != null)
			p_stmt.setQueryTimeout (seconds);
		else
			throw new java.lang.UnsupportedOperationException ("Method setQueryTimeout() not yet implemented.");
	}

	/**
	 * Method cancel
	 * @throws SQLException
	 * @see java.sql.Statement#cancel()
	 */
	public void cancel () throws SQLException
	{
		if (p_stmt != null)
			p_stmt.cancel();
		else
			throw new java.lang.UnsupportedOperationException ("Method cancel() not yet implemented.");
	}

	/**
	 * Method getWarnings
	 * @return SQLWarning
	 * @throws SQLException
	 * @see java.sql.Statement#getWarnings()
	 */
	public SQLWarning getWarnings () throws SQLException
	{
		if (p_stmt != null)
			return p_stmt.getWarnings();
		throw new java.lang.UnsupportedOperationException ("Method getWarnings() not yet implemented.");
	}

	/**
	 * Method clearWarnings
	 * @throws SQLException
	 * @see java.sql.Statement#clearWarnings()
	 */
	public void clearWarnings () throws SQLException
	{
		if (p_stmt != null)
			p_stmt.clearWarnings();
		else
			throw new java.lang.UnsupportedOperationException ("Method clearWarnings() not yet implemented.");
	}

	/**
	 * Method setCursorName
	 * @param name String
	 * @throws SQLException
	 * @see java.sql.Statement#setCursorName(String)
	 */
	public void setCursorName (String name) throws SQLException
	{
		if (p_stmt != null)
			p_stmt.setCursorName(name);
		else
			throw new java.lang.UnsupportedOperationException ("Method setCursorName() not yet implemented.");
	}


	/**
	 * Method getResultSet
	 * @return ResultSet
	 * @throws SQLException
	 * @see java.sql.Statement#getResultSet()
	 */
	public ResultSet getResultSet () throws SQLException
	{
		if (p_stmt != null)
			return p_stmt.getResultSet();
		throw new java.lang.UnsupportedOperationException ("Method getResultSet() not yet implemented.");
	}

	/**
	 * Method getUpdateCount
	 * @return int
	 * @throws SQLException
	 * @see java.sql.Statement#getUpdateCount()
	 */
	public int getUpdateCount () throws SQLException
	{
		if (p_stmt != null)
			return p_stmt.getUpdateCount();
		throw new java.lang.UnsupportedOperationException ("Method getUpdateCount() not yet implemented.");
	}

	/**
	 * Method getMoreResults
	 * @return boolean
	 * @throws SQLException
	 * @see java.sql.Statement#getMoreResults()
	 */
	public boolean getMoreResults () throws SQLException
	{
		if (p_stmt != null)
			return p_stmt.getMoreResults();
		throw new java.lang.UnsupportedOperationException ("Method getMoreResults() not yet implemented.");
	}

	/**
	 * Method setFetchDirection
	 * @param direction int
	 * @throws SQLException
	 * @see java.sql.Statement#setFetchDirection(int)
	 */
	public void setFetchDirection (int direction) throws SQLException
	{
		if (p_stmt != null)
			p_stmt.setFetchDirection(direction);
		else
			throw new java.lang.UnsupportedOperationException ("Method setFetchDirection() not yet implemented.");
	}

	/**
	 * Method getFetchDirection
	 * @return int
	 * @throws SQLException
	 * @see java.sql.Statement#getFetchDirection()
	 */
	public int getFetchDirection () throws SQLException
	{
		if (p_stmt != null)
			return p_stmt.getFetchDirection();
		throw new java.lang.UnsupportedOperationException ("Method getFetchDirection() not yet implemented.");
	}

	/**
	 * Method setFetchSize
	 * @param rows int
	 * @throws SQLException
	 * @see java.sql.Statement#setFetchSize(int)
	 */
	public void setFetchSize (int rows) throws SQLException
	{
		if (p_stmt != null)
			p_stmt.setFetchSize(rows);
		else
			throw new java.lang.UnsupportedOperationException ("Method setFetchSize() not yet implemented.");
	}

	/**
	 * Method getFetchSize
	 * @return int
	 * @throws SQLException
	 * @see java.sql.Statement#getFetchSize()
	 */
	public int getFetchSize () throws SQLException
	{
		if (p_stmt != null)
			return p_stmt.getFetchSize();
		throw new java.lang.UnsupportedOperationException ("Method getFetchSize() not yet implemented.");
	}

	/**
	 * Method getResultSetConcurrency
	 * @return int
	 * @throws SQLException
	 * @see java.sql.Statement#getResultSetConcurrency()
	 */
	public int getResultSetConcurrency () throws SQLException
	{
		if (p_stmt != null)
			return p_stmt.getResultSetConcurrency();
		throw new java.lang.UnsupportedOperationException ("Method getResultSetConcurrency() not yet implemented.");
	}

	/**
	 * Method getResultSetType
	 * @return int
	 * @throws SQLException
	 * @see java.sql.Statement#getResultSetType()
	 */
	public int getResultSetType () throws SQLException
	{
		if (p_stmt != null)
			return p_stmt.getResultSetType();
		throw new java.lang.UnsupportedOperationException ("Method getResultSetType() not yet implemented.");
	}

	/**
	 * 	Close
	 * 	@throws SQLException
	 * @see java.sql.Statement#close()
	 */
	public void close () throws SQLException
	{
        if (p_stmt != null)
        {
            Connection conn = p_stmt.getConnection();
            p_stmt.close();
            
            if (!close && !useTransactionConnection)
            {
                conn.close();
            }
        }
        close = true;
	}	//	close
	
	/**
	 * 
	 * @return boolean
	 * @throws SQLException
	 */
	protected boolean remote_execute() throws SQLException {
		//	Client -> remote sever
		log.finest("server => " + p_vo + ", Remote=" + DB.isRemoteObjects());
		try
		{
			if (DB.isRemoteObjects() && CConnection.get().isAppsServerOK(false))
			{
				Server server = CConnection.get().getServer();
				if (server != null)
				{
					executeResult = server.stmt_execute(p_vo, SecurityToken.getInstance());
					p_vo.clearParameters();		//	re-use of result set
					return executeResult.isFirstResult();
				}
				log.log(Level.SEVERE, "AppsServer not found");
			}
			throw new IllegalStateException("Remote Connection - Application server not available");
		}
		catch (Exception ex)
		{
			log.log(Level.SEVERE, "AppsServer error", ex);
			if (ex instanceof SQLException)
				throw (SQLException)ex;
			else if (ex instanceof RuntimeException)
				throw (RuntimeException)ex;
			else
				throw new RuntimeException(ex);
		}
	}

	/**
	 * 	Execute Query
	 * 	@return ResultSet or RowSet
	 * 	@throws SQLException
	 * @see java.sql.PreparedStatement#executeQuery()
	 */
	public RowSet getRowSet()
	{
		if (p_stmt != null)	//	local
			return local_getRowSet();
		//
		//	Client -> remote sever
		log.finest("server => " + p_vo + ", Remote=" + DB.isRemoteObjects());
		try
		{
			boolean remote = DB.isRemoteObjects() && CConnection.get().isAppsServerOK(false);
			if (remote && p_remoteErrors > 1)
				remote = CConnection.get().isAppsServerOK(true);
			if (remote)
			{
				Server server = CConnection.get().getServer();
				if (server != null)
				{
					RowSet rs = server.stmt_getRowSet (p_vo, SecurityToken.getInstance());
					p_vo.clearParameters();		//	re-use of result set
					if (rs == null)
						log.warning("RowSet is null - " + p_vo);
					else
						p_remoteErrors = 0;
					return rs;
				}
				log.log(Level.SEVERE, "AppsServer not found");
				p_remoteErrors++;
			}
		}
		catch (Exception ex)
		{
			log.log(Level.SEVERE, "AppsServer error", ex);
			p_remoteErrors++;
			if (ex instanceof RuntimeException)
				throw (RuntimeException)ex;
			else
				throw new RuntimeException(ex);
		}
		throw new IllegalStateException("Remote Connection - Application server not available");
	}
	
	/*************************************************************************
	 * 	Get Result as RowSet for Remote.
	 * 	Note that close the oracle OracleCachedRowSet also close connection!
	 *	@return result as RowSet
	 */
	protected RowSet local_getRowSet()
	{
		log.finest("local_getRowSet");
		RowSet rowSet = null;
		ResultSet rs = null;
		try
		{
			rs = p_stmt.executeQuery(p_vo.getSql());
			rowSet = CCachedRowSet.getRowSet(rs);			
		}
		catch (Exception ex)
		{
			log.log(Level.SEVERE, p_vo.toString(), ex);
			throw new RuntimeException (ex);
		}		
		finally
		{
			DB.close(rs);
		}
		return rowSet;
	}	//	local_getRowSet
        
    public boolean isPoolable() throws SQLException{ return false;}
      
    public void setPoolable(boolean a) throws SQLException{};
       
    public boolean isClosed() throws SQLException{ return close;}
       
    public boolean isWrapperFor(java.lang.Class c) throws SQLException{ return false;}
       
    public <T> T unwrap(java.lang.Class<T> iface) throws java.sql.SQLException{return null;}

    /**
     * 
     * @return is using transaction connection
     */
	public boolean isUseTransactionConnection() {
		return useTransactionConnection;
	}

}	//	CStatement
