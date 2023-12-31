/*
 * ARX: Powerful Data Anonymization
 * Copyright 2014-2015 Karol Babioch, Fabian Prasser
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.deidentifier.arx.gui.view.impl.wizard;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.deidentifier.arx.DataType;
import org.deidentifier.arx.gui.resources.Resources;
import org.deidentifier.arx.gui.view.SWTUtil;
import org.deidentifier.arx.io.ImportColumnJDBC;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnViewerToolTipSupport;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.window.ToolTip;
import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;

/**
 * Table overview page
 * 
 * This pages gives the user an overview of the detected tables and allows him
 * to select the desired one by clicking on it. The tables itself are retrieved
 * from {@link ImportData#getJdbcTables()()}. The selected one will be assigned
 * via {@link ImportWizardModel#setSelectedJdbcTable(String)} along with the
 * detected columns for this table using {@link ImportWizardModel#setWizardColumns(List)} and its preview data {@link ImportWizardModel#setPreviewData(List)}.
 * 
 * @author Karol Babioch
 * @author Fabian Prasser
 */
public class ImportWizardPageTable extends WizardPage {
    
    /**
     * Returns a human readable string representation of <code>rows</code>
     *
     * This converts rows into a human readable string, e.g. 1000000 gets
     * converted to 1M.
     *
     * The code is based upon <a href="http://bit.ly/1m4UetX">this</a> snippet.
     *
     * @param rows The number of rows to be converted
     *
     * @return Human readable string representation of <code>rows</code>
     */
    private static String humanReadableRowCount(long rows) {
        
        int unit = 1000;
        if (rows < unit) {
            return new Long(rows).toString();
        } else {
            int exp = (int) (Math.log(rows) / Math.log(unit));
            char pre = "kMGTPE".charAt(exp - 1); //$NON-NLS-1$
            return String.format("%.1f%s", rows / Math.pow(unit, exp), pre); //$NON-NLS-1$
        }
    }
    
    /** Reference to the wizard containing this page. */
    private ImportWizard wizardImport;
    
    /* SWT Widgets */
    /** TODO */
    private Table        table;
    
    /** TODO */
    private TableViewer  tableViewer;
    
    /**
     * Creates a new instance of this page and sets its title and description.
     *
     * @param wizardImport Reference to wizard containing this page
     */
    public ImportWizardPageTable(ImportWizard wizardImport) {
        
        super("WizardImportTablePage"); //$NON-NLS-1$
        this.wizardImport = wizardImport;
        setTitle(Resources.getMessage("ImportWizardPageTable.3")); //$NON-NLS-1$
        setDescription(Resources.getMessage("ImportWizardPageTable.4")); //$NON-NLS-1$
    }
    
    /**
     * Creates the design of this page along with the appropriate listeners.
     *
     * @param parent
     */
    public void createControl(Composite parent) {
        
        Composite container = new Composite(parent, SWT.NULL);
        
        setControl(container);
        container.setLayout(new GridLayout(1, false));
        
        /* TableViewer for the detected tables */
        tableViewer = SWTUtil.createTableViewer(container, SWT.BORDER | SWT.FULL_SELECTION);
        tableViewer.setContentProvider(new ArrayContentProvider());
        ColumnViewerToolTipSupport.enableFor(tableViewer, ToolTip.NO_RECREATE);
        tableViewer.addSelectionChangedListener(new ISelectionChangedListener() {
            
            /**
             * Reads in the columns and preview data for selected table
             */
            @Override
            public void selectionChanged(SelectionChangedEvent arg0) {
                
                /* Save selected table */
                int index = table.getSelectionIndex();
                String selectedTable = wizardImport.getData()
                                                   .getJdbcTables()
                                                   .get(index);
                wizardImport.getData().setSelectedJdbcTable(selectedTable);
                
                readColumns();
                readPreview();
                
                setPageComplete(true);
            }
        });
        
        /* Table for {@link #tableViewer} */
        table = tableViewer.getTable();
        table.setHeaderVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 1, 1));
        
        /* Column for table names */
        TableViewerColumn tableViewerColumnName = new TableViewerColumn(tableViewer,
                                                                        SWT.NONE);
        tableViewerColumnName.setLabelProvider(new ColumnLabelProvider() {
            
            /** Returns table name */
            @Override
            public String getText(Object element) {
                return (String) element;
            }
        });
        
        TableColumn tblclmnColumnName = tableViewerColumnName.getColumn();
        tblclmnColumnName.setToolTipText(Resources.getMessage("ImportWizardPageTable.5")); //$NON-NLS-1$
        tblclmnColumnName.setWidth(300);
        tblclmnColumnName.setText(Resources.getMessage("ImportWizardPageTable.6")); //$NON-NLS-1$
        
        TableViewerColumn tableViewerColumnColumns = new TableViewerColumn(tableViewer,
                                                                           SWT.NONE);
        tableViewerColumnColumns.setLabelProvider(new ColumnLabelProvider() {
            
            /**
             * Returns number of columns for table
             * 
             * If the number of columns couldn't be determined, three question
             * marks are returned.
             */
            @Override
            public String getText(Object element) {
                
                int columns = getNumberOfColumns((String) element);
                if (columns != -1) {
                    return "" + columns; //$NON-NLS-1$
                } else {
                    return "???"; //$NON-NLS-1$
                }
            }
            
        });
        
        TableColumn tblclmnColumns = tableViewerColumnColumns.getColumn();
        tblclmnColumns.setToolTipText(Resources.getMessage("ImportWizardPageTable.9")); //$NON-NLS-1$
        tblclmnColumns.setWidth(100);
        tblclmnColumns.setText(Resources.getMessage("ImportWizardPageTable.10")); //$NON-NLS-1$
        
        TableViewerColumn tableViewerColumnRows = new TableViewerColumn(tableViewer,
                                                                        SWT.NONE);
        tableViewerColumnRows.setLabelProvider(new ColumnLabelProvider() {
            
            /**
             * Returns number of rows for table
             * 
             * If the number of rows couldn't be determined, three question
             * marks are returned.
             */
            @Override
            public String getText(Object element) {
                
                long rows = getNumberOfRows((String) element);
                if (rows != -1) {
                    return " ~ " + humanReadableRowCount(rows); //$NON-NLS-1$
                } else {
                    return "???"; //$NON-NLS-1$
                }
            }
            
            /**
             * Returns the exact number of rows as tooltip
             *
             * This will return the exact number of rows for tables with a
             * row count greater than thousand, as the column itself will
             * only show a human readable string.
             *
             * @see #getText(Object)
             * @see #getNumberOfRows(String)
             */
            @Override
            public String getToolTipText(Object element) {
                
                long rows = getNumberOfRows((String) element);
                if (rows > 1000) {
                    return "" + rows; //$NON-NLS-1$
                } else {
                    return null;
                }
            }
        });
        
        TableColumn tblclmnRows = tableViewerColumnRows.getColumn();
        tblclmnRows.setToolTipText(Resources.getMessage("ImportWizardPageTable.14")); //$NON-NLS-1$
        tblclmnRows.setWidth(100);
        tblclmnRows.setText(Resources.getMessage("ImportWizardPageTable.15")); //$NON-NLS-1$
        
        setPageComplete(false);
    }
    
    /**
     * Applies previously detected tables to {@link #tableViewer}.
     *
     * @param visible
     */
    @Override
    public void setVisible(boolean visible) {
        
        super.setVisible(visible);
        
        if (visible) {
            tableViewer.setInput(wizardImport.getData().getJdbcTables());
            
            /* Mark page as complete when table has been selected before */
            if (wizardImport.getData().getSelectedJdbcTable() != null) {
                setPageComplete(true);
            }
        } else {
            setPageComplete(false);
        }
    }
    
    /**
     * Reads in the columns of currently selected table
     * 
     * If this can be performed successful, the columns will be made available
     * for the next page by {@link ImportWizardModel#setWizardColumns(List)}.
     * Otherwise an appropriate error message is set.
     */
    private void readColumns() {
        
        String selectedTable = wizardImport.getData().getSelectedJdbcTable();
        
        Connection connection = wizardImport.getData().getJdbcConnection();
        List<ImportWizardModelColumn> columns = new ArrayList<ImportWizardModelColumn>();
        
        int i = 0;
        try {
            ResultSet rs = connection.getMetaData().getColumns(null,
                                                               null,
                                                               selectedTable,
                                                               null);
            
            while (rs.next()) {
                ImportColumnJDBC column = new ImportColumnJDBC(i++,
                                                               rs.getString("COLUMN_NAME"), //$NON-NLS-1$
                                                               DataType.STRING);
                columns.add(new ImportWizardModelColumn(column));
            }
            
        } catch (SQLException e) {
            setErrorMessage(Resources.getMessage("ImportWizardPageTable.17")); //$NON-NLS-1$
        }
        
        wizardImport.getData().setWizardColumns(columns);
    }
    
    /**
     * Gets the number of columns for given table
     *
     * This uses the JDBC connection {@link ImportWizardModel#getJdbcConnection()} to determine the number of
     * columns for given table.
     *
     * @param table
     *            Table number of rows should be returned for
     *
     * @return Number of rows for given table, -1 in case of error
     */
    protected int getNumberOfColumns(String table)
    {
        ResultSet rs = null;
        int i = 0;
        
        try {
            
            Connection connection = wizardImport.getData().getJdbcConnection();
            rs = connection.getMetaData().getColumns(null,
                                                     null,
                                                     table,
                                                     null);
            while (rs.next()) {
                i++;
            }
            
        } catch (SQLException e) {
            e.printStackTrace();
            setErrorMessage(Resources.getMessage("ImportWizardPageTable.18")); //$NON-NLS-1$
        } finally {
            try {
                if (rs != null && !rs.isClosed()) {
                    rs.close();
                }
            } catch (SQLException e) {
                /* Ignore silently */
            }
        }
        
        return i;
        
    }
    
    /**
     * Gets the number of rows for given table
     * 
     * This uses the JDBC connection {@link ImportWizardModel#getJdbcConnection()} to determine the number of
     * rows for given table.
     * 
     * @param table
     *            Table number of rows should be returned for
     * 
     * @return Number of rows for given table, -1 in case of error
     */
    protected long getNumberOfRows(String table) {
        
        Statement statement = null;
        ResultSet resultSet = null;
        
        try {
            statement = wizardImport.getData()
                                    .getJdbcConnection()
                                    .createStatement();
            statement.execute("SELECT COUNT(*) FROM " + table); //$NON-NLS-1$
            resultSet = statement.getResultSet();
            
            if (resultSet.next()) {
                return resultSet.getLong(1);
            }
            
        } catch (SQLException e) {
            /* Ignore silently */
        } finally {
            try {
                if (statement != null && !statement.isClosed()) {
                    statement.close();
                }
                if (resultSet != null && !resultSet.isClosed()) {
                    resultSet.close();
                }
                
            } catch (SQLException e) {
                /* Ignore silently */
            }
        }
        
        return -1L;
    }
    
    /**
     * Reads in the preview data for currently selected table
     * 
     * If this can be performed successful, the preview data will be made
     * available for the following pages by {@link ImportWizardModel#setPreviewData(List)}. Otherwise an appropriate
     * error message is set.
     */
    protected void readPreview() {
        
        String selectedTable = wizardImport.getData().getSelectedJdbcTable();
        
        List<String[]> previewData = new ArrayList<String[]>();
        Connection connection = wizardImport.getData().getJdbcConnection();
        
        try {
            
            Statement statement = connection.createStatement();
            statement.setMaxRows(ImportWizardModel.PREVIEW_MAX_LINES);
            statement.execute("SELECT * FROM " + selectedTable); //$NON-NLS-1$
            ResultSet rs = statement.getResultSet();
            
            while (rs.next()) {
                String[] previewRow = new String[rs.getMetaData()
                                                   .getColumnCount()];
                
                for (int j = 0; j < previewRow.length; j++) {
                    previewRow[j] = rs.getString(j + 1);
                }
                previewData.add(previewRow);
            }
        } catch (SQLException e) {
            setErrorMessage(Resources.getMessage("ImportWizardPageTable.21")); //$NON-NLS-1$
        }
        
        wizardImport.getData().setPreviewData(previewData);
        
    }
}
