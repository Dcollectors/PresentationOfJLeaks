package org.sql2o;

import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sql2o.converters.Convert;
import org.sql2o.converters.Converter;
import org.sql2o.converters.ConverterException;
import org.sql2o.data.Table;
import org.sql2o.data.TableFactory;
import org.sql2o.reflection.Pojo;
import org.sql2o.reflection.PojoMetadata;
import org.sql2o.tools.NamedParameterStatement;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.*;
import java.sql.Date;
import java.util.*;

/**
 * Represents a sql2o statement. With sql2o, all statements are instances of the Query class.
 */
public class Query {

    private final Logger logger = LoggerFactory.getLogger(Query.class);

    public Query(Connection connection, String queryText, String name, boolean returnGeneratedKeys) {
        this.connection = connection;
        this.name = name;
        this.returnGeneratedKeys = returnGeneratedKeys;

        try{
            statement = new NamedParameterStatement(connection.getJdbcConnection(), queryText, returnGeneratedKeys);
        }
        catch(Exception ex){
            throw new RuntimeException(ex);
        }

        this.setColumnMappings(connection.getSql2o().getDefaultColumnMappings());
        this.caseSensitive = connection.getSql2o().isDefaultCaseSensitive();
        this.methodsMap = new HashMap<String, Method>();
    }

    private Connection connection;

    private Map<String, String> caseSensitiveColumnMappings;
    private Map<String, String> columnMappings;
    private Map<String, Method> methodsMap;

    private NamedParameterStatement statement;

    private boolean caseSensitive;
    private boolean autoDeriveColumnNames;
    
    private final String name;
    private boolean returnGeneratedKeys;

    public Query addParameter(String name, Object value){
        try{
            statement.setObject(name, value);
        }
        catch(SQLException ex){
            throw new RuntimeException(ex);
        }
        return this;
    }

    public Query addParameter(String name, InputStream value){
        try{
            statement.setInputStream(name, value);
        }
        catch(SQLException ex){
            throw new RuntimeException(ex);
        }
        return this;
    }
    
    public Query addParameter(String name, int value){
        try{
            statement.setInt(name, value);
        }
        catch (SQLException ex){
            throw new Sql2oException(ex);
        }
        return this;
    }

    public Query addParameter(String name, Integer value){
        try{
            if (value == null){
                statement.setNull(name, Types.INTEGER);
            }else{
                statement.setInt(name, value);
            }
        }
        catch(SQLException ex){
            throw new RuntimeException(ex);
        }
        return this;
    }

    public Query addParameter(String name, long value){
        try{
            statement.setLong(name, value);
        }
        catch(SQLException ex){
            throw new RuntimeException(ex);
        }
        return this;
    }
    
    public Query addParameter(String name, Long value){
        try{
            if (value == null){
                statement.setNull(name, Types.INTEGER);
            } else {
                statement.setLong(name, value);
            }
        }
        catch (SQLException ex){
            throw new Sql2oException(ex);
        }
        return this;
    }

    public Query addParameter(String name, String value){
        try{
            if (value == null){
                statement.setNull(name, Types.VARCHAR);
            }else{
                statement.setString(name, value);
            }
        }
        catch(Exception ex){
            throw new RuntimeException(ex);
        }
        return this;
    }

    public Query addParameter(String name, Timestamp value){
        try{
            if (value == null){
                statement.setNull(name, Types.TIMESTAMP);
            } else {
                statement.setTimestamp(name, value);
            }
        }
        catch(Exception ex){
            throw new RuntimeException(ex);
        }
        return this;
    }

    public Query addParameter(String name, Date value){
        if (this.getConnection().getSql2o().quirksMode == QuirksMode.MSSqlServer){
            Timestamp timestamp = value == null ? null : new Timestamp(value.getTime());
            return addParameter(name, timestamp);
        }

        if (value != null && this.connection.getSql2o().quirksMode == QuirksMode.DB2){
            // With the DB2 driver you can get an error if trying to put a date value into a timestamp column,
            // but of some reason it works if using setObject().
            return addParameter(name, (Object)value);
        }

        try{
            if (value == null) {
                statement.setNull(name, Types.DATE);
            } else {
                statement.setDate(name, value);
            }
        } catch (Exception ex) {
            throw new Sql2oException("Error setting parameter '" + name + "'", ex);
        }

        return this;
    }

    public Query addParameter(String name, java.util.Date value){
        Date sqlDate = value == null ? null : new Date(value.getTime());
        return addParameter(name, sqlDate);

    }

    public Query addParameter(String name, Time value){
        try {
            if (value == null){
                statement.setNull(name, Types.TIME);
            } else {
                statement.setTime(name,value);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return this;
    }

    public Query addParameter(String name, DateTime value){
        Timestamp timestamp = value == null ? null : new Timestamp(value.toDate().getTime());
        return addParameter(name, timestamp);
    }

    public Query addParameter(String name, Enum value) {
        String strVal = value == null ? null : value.toString();
        return addParameter(name, strVal);
    }
    
    public Query bind(Object bean){
        Class clazz = bean.getClass();
        Method[] methods = clazz.getDeclaredMethods();
        for(Method method : methods){
            try{
                method.setAccessible(true);
                String methodName = method.getName();
                /*
                It looks in the class for all the methods that start with get
                */
                if(methodName.startsWith("get") && method.getParameterTypes().length == 0){
                    String param = methodName.substring(3);//remove the get prefix
                    param = param.substring(0, 1).toLowerCase() + param.substring(1);//set the first letter in Lowercase => so getItem produces item
                    Object res = method.invoke(bean);
                    if( res!= null){
                        try {
                            Method addParam = this.getClass().getDeclaredMethod("addParameter", param.getClass(), method.getReturnType());
                            addParam.invoke(this, param, res);
                        } catch (NoSuchMethodException ex) {
                            logger.debug("Using addParameter(String, Object)", ex);
                            addParameter(param, res);
                        }
                    }else
                        addParameter(param, res);
                }
            }catch(IllegalArgumentException ex){
                logger.debug("Ignoring Illegal Arguments", ex);
            }catch(IllegalAccessException ex){
                throw new RuntimeException(ex);
            } catch (SecurityException ex) {
                throw new RuntimeException(ex);
            } catch (InvocationTargetException ex) {
                throw new RuntimeException(ex);
            }
        }
        return this;
    }

    public boolean isCaseSensitive() {
        return caseSensitive;
    }

    public Query setCaseSensitive(boolean caseSensitive) {
        this.caseSensitive = caseSensitive;
        return this;
    }

    public boolean isAutoDeriveColumnNames() {
    	return autoDeriveColumnNames;
    }
    
    public Query setAutoDeriveColumnNames(boolean autoDeriveColumnNames) {
    	this.autoDeriveColumnNames = autoDeriveColumnNames;
    	return this;
    }
    
    public Connection getConnection(){
        return this.connection;
    }

    public String getName() {
        return name;
    }

    public <T> Iterable<T> executeAndFetchLazy(Class<T> returnType) {
        final PojoMetadata metadata = new PojoMetadata(returnType, this.isCaseSensitive(), this.isAutoDeriveColumnNames(), this.getColumnMappings());
        final boolean isCaseSensitive = this.isCaseSensitive();
        final QuirksMode quirksMode = this.connection.getSql2o().quirksMode;
        final String name = this.getName() == null ? "No name" : this.getName();

        final Iterator<T> resultSetIterator = new Iterator<T>() {
            private T next; // keep track of next item in case hasNext() is called multiple times

            private long start;
            private ResultSet rs;
            private long afterExecQuery;
            private ResultSetMetaData meta;

            public boolean hasNext() {
                // check if we already fetched next item
                if (next != null) {
                    return true;
                }

                // initialize if necessary
                if (meta == null) {
                    initialize();
                }

                // check if result set already closed
                if (rs == null) {
                    return false;
                }

                // now fetch next item
                next = readNext();

                // check if we got something
                if (next != null) {
                    return true;
                }

                // no more items, close up ResultSet
                close();

                // log the query
                long afterClose = System.currentTimeMillis();
                logger.debug("total: {} ms, execution: {} ms, reading and parsing: {} ms; executed [{}]", new Object[]{
                        afterClose - start,
                        afterExecQuery-start,
                        afterClose - afterExecQuery,
                        name
                });

                return false;
            }

            public T next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }

                T result = next;

                next = null;

                return result;
            }

            public void remove() {
                throw new UnsupportedOperationException();
            }

            private void initialize() {
                try {
                    start = System.currentTimeMillis();
                    rs = statement.executeQuery();
                    afterExecQuery = System.currentTimeMillis();
                    meta = rs.getMetaData();
                }
                catch(SQLException ex) {
                    close();
                    throw new Sql2oException("Database error: " + ex.getMessage(), ex);
                }
            }

            private T readNext() {
                try {
                    if (rs.next()) {
                        Pojo pojo = new Pojo(metadata, isCaseSensitive);

                        for(int colIdx = 1; colIdx <= meta.getColumnCount(); colIdx++) {
                            String colName;
                            if (quirksMode == QuirksMode.DB2){
                                colName = meta.getColumnName(colIdx);
                            } else {
                                colName = meta.getColumnLabel(colIdx);
                            }
                            pojo.setProperty(colName, getRSVal(rs, colIdx));
                        }

                        return (T)pojo.getObject();
                    }
                }
                catch (SQLException ex) {
                    throw new Sql2oException("Database error: " + ex.getMessage(), ex);
                }
                return null;
            }

            private void close() {
                try {
                    if (rs != null && !rs.isClosed()) {
                        rs.close();
                    }
                }
                catch (SQLException ex) {
                    throw new Sql2oException("Error attempting to close ResultSet.", ex);
                }
                finally {
                    rs = null;
                    closeConnectionIfNecessary();
                }
            }

        };

        return new Iterable<T>() {
            public Iterator<T> iterator() {
                return resultSetIterator;
            }
        };
    }

    public <T> List<T> executeAndFetch(Class<T> returnType){
        List<T> list = new ArrayList<T>();
        for (T item : executeAndFetchLazy(returnType)) {
            list.add(item);
        }
        return list;
    }

    public <T> T executeAndFetchFirst(Class<T> returnType){
        Iterator<T> iterator = executeAndFetchLazy(returnType).iterator();
        if (iterator.hasNext()) {
            return iterator.next();
        }
        else {
            return null;
        }
    }
    
    public Table executeAndFetchTable(){
        ResultSet rs;
        long start = System.currentTimeMillis();
        try {
            rs = statement.executeQuery();
            long afterExecute = System.currentTimeMillis();
            Table table = TableFactory.createTable(rs, this.isCaseSensitive(), this.connection.getSql2o().quirksMode);
            long afterClose = System.currentTimeMillis();
            
            logger.debug("total: {} ms, execution: {} ms, reading and parsing: {} ms; executed fetch table [{}]", new Object[]{
                afterClose - start, 
                afterExecute-start, 
                afterClose - afterExecute, 
                this.getName() == null ? "No name" : this.getName()
            });
            
            return table;
        } catch (SQLException e) {
            throw new Sql2oException("Error while executing query", e);
        } finally {
            closeConnectionIfNecessary();
        }
    }

    public Connection executeUpdate(){
        long start = System.currentTimeMillis();
        try{

            this.connection.setResult(statement.executeUpdate());
            this.connection.setKeys(this.returnGeneratedKeys ? statement.getStatement().getGeneratedKeys() : null);
            connection.setCanGetKeys(this.returnGeneratedKeys);
        }
        catch(SQLException ex){
            this.connection.onException();
            throw new Sql2oException("Error in executeUpdate, " + ex.getMessage(), ex);
        }
        finally {
            closeConnectionIfNecessary();
        }

        long end = System.currentTimeMillis();
        logger.debug("total: {} ms; executed update [{}]", new Object[]{
            end - start, 
            this.getName() == null ? "No name" : this.getName()
        });

        return this.connection;
    }

    public Object executeScalar(){
        long start = System.currentTimeMillis();
        try {
            ResultSet rs = this.statement.executeQuery();
            if (rs.next()){
                Object o = getRSVal(rs, 1);
                long end = System.currentTimeMillis();
                logger.debug("total: {} ms; executed scalar [{}]", new Object[]{
                    end - start, 
                    this.getName() == null ? "No name" : this.getName()
                });
                return o;
            }
            else{
                return null;
            }

        }
        catch (SQLException e) {
            this.connection.onException();
            throw new Sql2oException("Database error occurred while running executeScalar: " + e.getMessage(), e);
        }
        finally{
            closeConnectionIfNecessary();
        }
        
    }
    
    public <V> V executeScalar(Class returnType){
        Object value = executeScalar();
        Converter converter = null;
        try {
            converter = Convert.getConverter(returnType);
            return (V)converter.convert(value);
        } catch (ConverterException e) {
            throw new Sql2oException("Error occured while converting value from database to type " + returnType.toString(), e);
        }

    }

    public <T> List<T> executeScalarList(){
        long start = System.currentTimeMillis();
        List<T> list = new ArrayList<T>();
        try{
            ResultSet rs = this.statement.executeQuery();
            while(rs.next()){
                list.add((T)getRSVal(rs,1));
            }

            long end = System.currentTimeMillis();
            logger.debug("total: {} ms; executed scalar list [{}]", new Object[]{
                end - start,
                this.getName() == null ? "No name" : this.getName()
            });

            return list;
        }
        catch(SQLException ex){
            this.connection.onException();
            throw new Sql2oException("Error occurred while executing scalar list: " + ex.getMessage(), ex);
        }
        finally{
            closeConnectionIfNecessary();
        }
    }

    /************** batch stuff *******************/

    public Query addToBatch(){
        try {
            statement.addBatch();
        } catch (SQLException e) {
            throw new Sql2oException("Error while adding statement to batch", e);
        }

        return this;
    }

    public Connection executeBatch() throws Sql2oException {
        long start = System.currentTimeMillis();
        try {
            connection.setBatchResult( statement.executeBatch() );
        }
        catch (Throwable e) {
            this.connection.onException();
            throw new Sql2oException("Error while executing batch operation: " + e.getMessage(), e);
        }
        finally {
            closeConnectionIfNecessary();
        }

        long end = System.currentTimeMillis();
        logger.debug("total: {} ms; executed batch [{}]", new Object[]{
            end - start,
            this.getName() == null ? "No name" : this.getName()
        });

        return this.connection;
    }

    /*********** column mapping ****************/

    public Map<String, String> getColumnMappings() {
        if (this.isCaseSensitive()){
            return this.caseSensitiveColumnMappings;
        }
        else{
            return this.columnMappings;
        }
    }
    
    public Query setColumnMappings(Map<String, String> mappings){

        this.caseSensitiveColumnMappings = new HashMap<String, String>();
        this.columnMappings = new HashMap<String, String>();
        
        for (Map.Entry<String,String> entry : mappings.entrySet()){
            this.caseSensitiveColumnMappings.put(entry.getKey(), entry.getValue());
            this.columnMappings.put(entry.getKey().toLowerCase(), entry.getValue().toLowerCase());
        }
        
        return this;
    }

    public Query addColumnMapping(String columnName, String propertyName){
        this.caseSensitiveColumnMappings.put(columnName, propertyName);
        this.columnMappings.put(columnName.toLowerCase(), propertyName.toLowerCase());

        return this;
    }

    /************** private stuff ***************/
    private void closeConnectionIfNecessary(){
        try{
            if (!this.connection.getJdbcConnection().isClosed() && this.connection.getJdbcConnection().getAutoCommit() && statement != null){
                statement.close();
                this.connection.getJdbcConnection().close();
            }
        }
        catch (Exception ex){
            throw new RuntimeException("Error while attempting to close connection", ex);
        }
    }

    private Object getRSVal(ResultSet rs, int idx) throws SQLException {
        Object o = rs.getObject(idx);
        // oracle timestamps are not always convertible to a java Date. If ResultSet.getTimestamp is used instead of
        // ResultSet.getObject, a normal java.sql.Timestamp instance is returnd.
        if (o != null && o.getClass().getCanonicalName().startsWith("oracle.sql.TIMESTAMP")){
            o = rs.getTimestamp(idx);
        }

        return o;
    }

}
