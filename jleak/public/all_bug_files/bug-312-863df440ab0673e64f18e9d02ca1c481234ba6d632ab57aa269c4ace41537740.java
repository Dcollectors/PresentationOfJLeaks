/**
 * DataCleaner (community edition)
 * Copyright (C) 2013 Human Inference
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, write to:
 * Free Software Foundation, Inc.
 * 51 Franklin Street, Fifth Floor
 * Boston, MA  02110-1301  USA
 */
package org.eobjects.datacleaner.widgets.result;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.IOException;

import javax.inject.Inject;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.border.EmptyBorder;
import javax.swing.event.HyperlinkEvent;
import javax.swing.event.HyperlinkListener;

import org.eobjects.analyzer.beans.api.RendererBean;
import org.eobjects.analyzer.beans.writers.WriteDataResult;
import org.eobjects.analyzer.connection.Datastore;
import org.eobjects.analyzer.connection.DatastoreConnection;
import org.eobjects.analyzer.connection.FileDatastore;
import org.eobjects.analyzer.connection.ResourceDatastore;
import org.eobjects.analyzer.result.renderer.AbstractRenderer;
import org.eobjects.analyzer.result.renderer.SwingRenderingFormat;
import org.eobjects.datacleaner.actions.PreviewSourceDataActionListener;
import org.eobjects.datacleaner.bootstrap.WindowContext;
import org.eobjects.datacleaner.guice.DCModule;
import org.eobjects.datacleaner.panels.DCPanel;
import org.eobjects.datacleaner.user.MutableDatastoreCatalog;
import org.eobjects.datacleaner.util.IconUtils;
import org.eobjects.datacleaner.util.ImageManager;
import org.eobjects.datacleaner.widgets.Alignment;
import org.eobjects.datacleaner.windows.AnalysisJobBuilderWindow;
import org.apache.metamodel.schema.Table;
import org.apache.metamodel.util.FileResource;
import org.apache.metamodel.util.Resource;
import org.jdesktop.swingx.JXEditorPane;
import org.jdesktop.swingx.VerticalLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Guice;
import com.google.inject.Injector;

@RendererBean(SwingRenderingFormat.class)
public class WriteDataResultSwingRenderer extends AbstractRenderer<WriteDataResult, JComponent> {

    private static final Logger logger = LoggerFactory.getLogger(WriteDataResultSwingRenderer.class);
    private final ImageManager imageManager = ImageManager.get();

    @Inject
    WindowContext windowContext;

    @Inject
    DCModule _parentModule;

    @Inject
    MutableDatastoreCatalog _datastoreCatalog;

    @Override
    public JComponent render(WriteDataResult result) {
        final EmptyBorder border = new EmptyBorder(10, 10, 10, 10);

        final DCPanel panel = new DCPanel();
        panel.setBorder(border);
        panel.setLayout(new VerticalLayout(4));

        int insertCount = result.getWrittenRowCount();
        int updateCount = result.getUpdatesCount();
        if (insertCount == 0 && updateCount == 0) {
            final JLabel label = new JLabel("No rows written!", imageManager.getImageIcon(IconUtils.STATUS_WARNING),
                    JLabel.LEFT);
            panel.add(label);
        } else {

            if (insertCount != 0) {
                final JLabel label = new JLabel(insertCount + " inserts executed!",
                        imageManager.getImageIcon(IconUtils.STATUS_VALID), JLabel.LEFT);
                panel.add(label);
            }

            if (updateCount != 0) {
                final JLabel label = new JLabel(updateCount + " updates executed!",
                        imageManager.getImageIcon(IconUtils.STATUS_VALID), JLabel.LEFT);
                panel.add(label);
            }

            final Datastore datastore = result.getDatastore(_datastoreCatalog);
            if (datastore != null && datastore instanceof ResourceDatastore) {
                final ResourceDatastore resourceDatastore = (ResourceDatastore) datastore;
                final Resource resource = resourceDatastore.getResource();
                if (resource != null && resource instanceof FileResource) {
                    final FileResource fileResource = (FileResource) resource;
                    final File file = fileResource.getFile();
                    if (file != null && file.exists()) {
                        final JXEditorPane editorPane = new JXEditorPane("text/html",
                                "Data was written to file: <a href=\"http://datacleaner.org/open_file\">"
                                        + file.getAbsolutePath() + "</a>.");
                        editorPane.setEditable(false);
                        editorPane.setOpaque(false);
                        editorPane.addHyperlinkListener(new HyperlinkListener() {
                            @Override
                            public void hyperlinkUpdate(HyperlinkEvent event) {
                                if (HyperlinkEvent.EventType.ACTIVATED.equals(event.getEventType())) {
                                    final String href = event.getDescription();
                                    if ("http://datacleaner.org/open_file".equals(href)) {
                                        try {
                                            Desktop.getDesktop().open(file);
                                        } catch (IOException ex) {
                                            logger.warn("Failed to open file: {}", file, ex);
                                        }
                                    }
                                }
                            }
                        });
                        panel.add(editorPane);
                    }
                }
            }

            final DCPanel buttonPanel = createButtonPanel(result);
            panel.add(buttonPanel);
        }

        if (result.getErrorRowCount() > 0) {
            final DCPanel errorRowsPanel = new DCPanel();
            errorRowsPanel.setLayout(new BorderLayout());

            final JLabel icon = new JLabel(imageManager.getImageIcon(IconUtils.STATUS_ERROR));
            errorRowsPanel.add(icon, BorderLayout.WEST);

            final FileDatastore errorDatastore = result.getErrorDatastore();

            final JXEditorPane editorPane = new JXEditorPane("text/html", "<b>" + result.getErrorRowCount()
                    + " records</b> could <i>not</i> be written to the table!<br/>"
                    + "The records were written to <a href=\"http://datacleaner.org/preview_datastore\">"
                    + errorDatastore.getFilename()
                    + "</a> (<a href=\"http://datacleaner.org/register_datastore\">Register as datastore</a>).");
            editorPane.setEditable(false);
            editorPane.setOpaque(false);
            editorPane.addHyperlinkListener(new HyperlinkListener() {
                @Override
                public void hyperlinkUpdate(HyperlinkEvent e) {
                    if (HyperlinkEvent.EventType.ACTIVATED.equals(e.getEventType())) {
                        final String href = e.getDescription();
                        if ("http://datacleaner.org/register_datastore".equals(href)) {
                            _datastoreCatalog.addDatastore(errorDatastore);
                            JOptionPane.showMessageDialog(editorPane, "Saved datastore: " + errorDatastore.getName());
                        } else if ("http://datacleaner.org/preview_datastore".equals(href)) {
                            DatastoreConnection errorCon = errorDatastore.openConnection();
                            try {
                                Table table = errorCon.getDataContext().getDefaultSchema().getTables()[0];
                                PreviewSourceDataActionListener actionListener = new PreviewSourceDataActionListener(
                                        windowContext, errorDatastore, table);
                                actionListener.actionPerformed(null);
                            } finally {
                                errorCon.close();
                            }
                        } else {
                            logger.error("Unexpected href: " + href + ". Event was: " + e);
                        }
                    }
                }
            });
            errorRowsPanel.add(editorPane, BorderLayout.CENTER);

            panel.add(errorRowsPanel);
        }

        return panel;
    }

    private DCPanel createButtonPanel(final WriteDataResult result) {
        final DCPanel panel = new DCPanel();
        panel.setLayout(new FlowLayout(Alignment.LEFT.getFlowLayoutAlignment(), 0, 4));

        final Datastore datastore = result.getDatastore(_datastoreCatalog);
        final Insets buttonMargin = new Insets(1, 4, 1, 4);
        if (datastore != null && datastore.getName() != null) {
            final Datastore ds = _datastoreCatalog.getDatastore(datastore.getName());
            if (!datastore.equals(ds)) {
                final JButton addDatastoreButton = new JButton("Add to datastores",
                        imageManager.getImageIcon(IconUtils.ACTION_ADD));
                addDatastoreButton.setMargin(buttonMargin);
                addDatastoreButton.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        _datastoreCatalog.addDatastore(datastore);
                        addDatastoreButton.setEnabled(false);
                    }
                });
                panel.add(addDatastoreButton);
                panel.add(Box.createHorizontalStrut(4));
            }

            final JButton analyzeButton = new JButton("Analyze this datastore",
                    imageManager.getImageIcon("images/filetypes/analysis_job.png"));
            analyzeButton.setMargin(buttonMargin);
            analyzeButton.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    Injector injector = Guice.createInjector(new DCModule(_parentModule, null));
                    AnalysisJobBuilderWindow window = injector.getInstance(AnalysisJobBuilderWindow.class);
                    window.setDatastore(datastore);
                    window.open();
                }
            });
            panel.add(analyzeButton);
            panel.add(Box.createHorizontalStrut(4));

            final JButton previewButton = new JButton("Preview table",
                    imageManager.getImageIcon("images/actions/preview_data.png"));
            previewButton.setMargin(buttonMargin);
            previewButton.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    final DatastoreConnection con = datastore.openConnection();
                    try {
                        con.getSchemaNavigator().refreshSchemas();
                        final Table previewTable = result.getPreviewTable(datastore);
                        if (previewTable == null) {
                            throw new IllegalStateException("Result did not return any preview table: " + result);
                        } else {
                            final PreviewSourceDataActionListener actionListener = new PreviewSourceDataActionListener(
                                    windowContext, datastore, previewTable);
                            actionListener.actionPerformed(null);
                        }
                    } finally {
                        con.close();
                    }
                }
            });
            panel.add(previewButton);
        }
        return panel;
    }
}
