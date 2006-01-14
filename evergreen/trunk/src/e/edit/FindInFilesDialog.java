package e.edit;

import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.tree.*;
import e.forms.*;
import e.gui.*;
import e.util.*;

import java.util.List;
import org.jdesktop.swingworker.SwingWorker;

public class FindInFilesDialog {
    private JTextField regexField = new JTextField(40);
    private JTextField filenameRegexField = new JTextField(40);
    private JLabel status = new JLabel(" ");
    private ETree matchView;
    private DefaultTreeModel matchTreeModel;
    
    private Workspace workspace;
    
    private FileFinder worker;
    private static final ExecutorService fileFinderExecutor = ThreadUtilities.newSingleThreadExecutor("Find in Files");
    
    private static final ExecutorService definitionFinderExecutor = ThreadUtilities.newFixedThreadPool(8, "Find Definitions");
    
    public interface ClickableTreeItem {
        public void open();
    }
    
    public class MatchingLine implements ClickableTreeItem {
        private String line;
        private File file;
        private Pattern pattern;
        
        public MatchingLine(String line, File file, Pattern pattern) {
            this.line = line;
            this.file = file;
            this.pattern = pattern;
        }
        
        public void open() {
            EWindow window = Edit.getInstance().openFile(file.toString());
            if (window instanceof ETextWindow) {
                ETextWindow textWindow = (ETextWindow) window;
                FindAction.INSTANCE.findInText(textWindow, PatternUtilities.toString(pattern));
                final int lineNumber = Integer.parseInt(line.substring(1, line.indexOf(':', 1)));
                textWindow.goToLine(lineNumber);
            }
        }
        
        public String toString() {
            return line;
        }
    }
    
    public class MatchingFile implements ClickableTreeItem {
        private File file;
        private String name;
        private int matchCount;
        private Pattern pattern;
        private boolean containsDefinition;
        
        /**
         * For matches based just on filename.
         */
        public MatchingFile(File file, String name) {
            this(file, name, 0, null);
        }
        
        /**
         * For matches based on filename and a regular expression.
         */
        public MatchingFile(File file, String name, int matchCount, Pattern pattern) {
            this.file = file;
            this.name = name;
            this.matchCount = matchCount;
            this.pattern = pattern;
            this.containsDefinition = containsDefinition;
            if (pattern != null) {
                definitionFinderExecutor.submit(new DefinitionFinder(file, pattern, this));
            }
        }
        
        public void setContainsDefinition(boolean newState) {
            this.containsDefinition = newState;
            // The check-in comment for revision 673 mentioned that JTree
            // caches node widths, and that changing the font for an individual
            // node could lead to unwanted clipping. The caching is done by
            // subclasses of javax.swing.tree.AbstractLayoutCache, and there
            // are methods called invalidatePathBounds and invalidateSizes, but
            // neither is easily accessible. We can access the first only via
            // the support for editable nodes. The latter we can access via
            // various setters. This relies on the fact that the setter
            // setRootVisible doesn't optimize out the case where the new state
            // is the same as the old state. We also need to explicitly request
            // a repaint. But this potentially fragile hack does let us work
            // around a visual glitch in the absence of an approved means of
            // invalidating the JTree UI delegate's layout cache. Having the
            // source is one of the things that makes Java great!
            EventQueue.invokeLater(new Runnable() {
                public void run() {
                    matchView.setRootVisible(true);
                    matchView.setRootVisible(false);
                    matchView.repaint();
                }
            });
        }
        
        public boolean containsDefinition() {
            return containsDefinition;
        }
        
        public void open() {
            EWindow window = Edit.getInstance().openFile(workspace.getRootDirectory() + File.separator + name);
            if (window instanceof ETextWindow && pattern != null) {
                ETextWindow textWindow = (ETextWindow) window;
                FindAction.INSTANCE.findInText(textWindow, PatternUtilities.toString(pattern));
                textWindow.getText().findNext();
            }
        }
        
        public String toString() {
            StringBuilder result = new StringBuilder(file.getName());
            if (matchCount != 0) {
                result.append(" (");
                result.append(StringUtilities.pluralize(matchCount, "match", " matches"));
                if (containsDefinition) {
                    result.append(" including definition");
                }
                result.append(")");
            }
            return result.toString();
        }
    }
    
    public class FileFinder extends SwingWorker<DefaultMutableTreeNode, DefaultMutableTreeNode> {
        private List<String> fileList;
        private DefaultMutableTreeNode matchRoot;
        private String regex;
        private String fileRegex;
        
        private HashMap<String, DefaultMutableTreeNode> pathMap = new HashMap<String, DefaultMutableTreeNode>();
        
        private int doneFileCount;
        private int matchingFileCount;
        private int totalFileCount;
        private int percentage;
        
        public FileFinder() {
            this.matchRoot = new DefaultMutableTreeNode();
            this.regex = regexField.getText();
            this.fileRegex = filenameRegexField.getText();
            this.fileList = workspace.getListOfFilesMatching(fileRegex);
            
            this.doneFileCount = 0;
            this.matchingFileCount = 0;
            this.totalFileCount = fileList.size();
            this.percentage = -1;
            
            matchTreeModel.setRoot(matchRoot);
        }
        
        private void updateStatus() {
            int newPercentage = (doneFileCount * 100) / totalFileCount;
            if (newPercentage != percentage) {
                percentage = newPercentage;
                setStatus("Searching... " + percentage + "%", false);
            }
        }
        
        @Override
        protected DefaultMutableTreeNode doInBackground() {
            Thread.currentThread().setName("Search for '" + regex + "' in files matching '" + fileRegex + "'");
            try {
                Pattern pattern = PatternUtilities.smartCaseCompile(regex);
                FileSearcher fileSearcher = new FileSearcher(pattern);
                String root = workspace.getRootDirectory();
                long startTime = System.currentTimeMillis();
                for (doneFileCount = 0; doneFileCount < fileList.size(); ++doneFileCount) {
                    if (isCancelled()) {
                        return null;
                    }
                    try {
                        String candidate = fileList.get(doneFileCount);
                        File file = FileUtilities.fileFromParentAndString(root, candidate);
                        if (regex.length() != 0) {
                            ArrayList<String> matches = new ArrayList<String>();
                            long t0 = System.currentTimeMillis();
                            boolean wasText = fileSearcher.searchFile(file, matches);
                            if (wasText == false) {
                                // FIXME: should we do the grep(1) thing of "binary file <x> matches"?
                                continue;
                            }
                            long t1 = System.currentTimeMillis();
                            if (t1 - t0 > 500) {
                                Log.warn("Searching file \"" + file + "\" for '" + regex + "' took " + (t1 - t0) + "ms!");
                            }
                            final int matchCount = matches.size();
                            if (matchCount > 0) {
                                DefaultMutableTreeNode pathNode = getPathNode(candidate);
                                MatchingFile matchingFile = new MatchingFile(file, candidate, matchCount, pattern);
                                DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(matchingFile);
                                for (String line : matches) {
                                    fileNode.add(new DefaultMutableTreeNode(new MatchingLine(line, file, pattern)));
                                }
                                pathNode.add(fileNode);
                                ++matchingFileCount;
                            }
                        } else {
                            DefaultMutableTreeNode pathNode = getPathNode(candidate);
                            pathNode.add(new DefaultMutableTreeNode(new MatchingFile(file, candidate)));
                        }
                    } catch (FileNotFoundException ex) {
                        ex = ex; // Not our problem.
                    }
                    
                    // Update our percentage-complete status, but only if we've
                    // taken enough time for the user to start caring, so we
                    // don't make quick searches unnecessarily slow.
                    if (System.currentTimeMillis() - startTime > 300) {
                        updateStatus();
                    }
                }
                long endTime = System.currentTimeMillis();
                Log.warn("Search for '" + regex + "' in files matching '" + fileRegex + "' took " + (endTime - startTime) + " ms.");
            } catch (PatternSyntaxException ex) {
                setStatus(ex.getDescription(), true);
            } catch (Exception ex) {
                Log.warn("Problem searching files for ", ex);
            }
            return matchRoot;
        }
        
        private DefaultMutableTreeNode getPathNode(String pathname) {
            String[] pathElements = pathname.split(File.separator);
            String pathSoFar = "";
            DefaultMutableTreeNode parentNode = matchRoot;
            DefaultMutableTreeNode node = matchRoot;
            for (int i = 0; i < pathElements.length - 1; ++i) {
                pathSoFar += pathElements[i] + File.separator;
                node = pathMap.get(pathSoFar);
                if (node == null) {
                    node = new DefaultMutableTreeNode(pathElements[i] + File.separator);
                    parentNode.add(node);
                    pathMap.put(pathSoFar, node);
                }
                parentNode = node;
            }
            return node;
        }
        
        @Override
        protected void done() {
            synchronized (FindInFilesDialog.this) {
                worker = null;
            }
            
            if (isCancelled()) {
                return;
            }
            
            String status = matchingFileCount + " / " + StringUtilities.pluralize(totalFileCount, "file", "files");
            if (workspace.getIndexedFileCount() != totalFileCount) {
                status += " (from " + workspace.getIndexedFileCount() + ")";
            }
            status += " match.";
            setStatus(status, false);
            
            matchView.expandAll();
        }
    }
    
    public static class DefinitionFinder implements Runnable, TagReader.TagListener {
        private File file;
        private MatchingFile matchingFile;
        private Pattern pattern;
        
        public DefinitionFinder(File file, Pattern pattern, MatchingFile matchingFile) {
            this.file = file;
            this.matchingFile = matchingFile;
            this.pattern = pattern;
        }
        
        public void run() {
            new TagReader(file, null, this);
        }
        
        public void tagFound(TagReader.Tag tag) {
            // Function prototypes and Java packages probably aren't interesting.
            if (tag.type == TagType.PROTOTYPE) {
               return;
            }
            if (tag.type == TagType.PACKAGE) {
               return;
            }
            if (pattern.matcher(tag.identifier).find()) {
                matchingFile.setContainsDefinition(true);
            }
        }
        
        public void taggingFailed(Exception ex) {
            Log.warn("Failed to use tags to check for a definition.", ex);
        }
    }
    
    public synchronized void showMatches() {
        if (worker != null) {
            worker.cancel(true);
        }
        
        worker = new FileFinder();
        fileFinderExecutor.submit(worker);
    }
    
    public void initMatchList() {
        if (matchView != null) {
            return;
        }

        matchTreeModel = new DefaultTreeModel(null);
        matchView = new ETree(matchTreeModel);
        matchView.setRootVisible(false);
        matchView.setShowsRootHandles(true);
        matchView.putClientProperty("JTree.lineStyle", "None");
        
        matchView.setCellRenderer(new MatchTreeCellRenderer());
        matchView.addTreeSelectionListener(new TreeSelectionListener() {
            public void valueChanged(TreeSelectionEvent e) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) matchView.getLastSelectedPathComponent();
                if (node == null) {
                    return;
                }
                
                // Directories are currently represented by String objects.
                Object userObject = node.getUserObject();
                if (userObject instanceof ClickableTreeItem) {
                    ClickableTreeItem match = (ClickableTreeItem) node.getUserObject();
                    match.open();
                }
            }
        });
    }
    
    public class MatchTreeCellRenderer extends DefaultTreeCellRenderer {
        Font defaultFont = null;
        
        public MatchTreeCellRenderer() {
            setClosedIcon(null);
            setOpenIcon(null);
            setLeafIcon(null);
        }
        
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean isSelected, boolean isExpanded, boolean isLeaf,  int row,  boolean hasFocus) {
            Component c = super.getTreeCellRendererComponent(tree, value, isSelected, isExpanded, isLeaf, row, hasFocus);
            
            // Unlike the foreground Color, the Font gets remembered, so we
            // need to manually revert it each time.
            if (defaultFont == null) {
                defaultFont = c.getFont();
            }
            c.setFont(defaultFont);
            
            // Work around JLabel's tab-rendering stupidity.
            String text = getText();
            if (text != null && text.contains("\t")) {
                setText(text.replaceAll("\t", "    "));
            }
            
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
            if (node.getUserObject() instanceof MatchingFile) {
                MatchingFile file = (MatchingFile) node.getUserObject();
                if (file.containsDefinition()) {
                    c.setFont(c.getFont().deriveFont(Font.BOLD));
                }
            } else {
                c.setForeground(Color.GRAY);
            }
            return c;
        }
    }
    
    private void setStatus(final String message, final boolean isError) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                status.setForeground(isError ? Color.RED : Color.BLACK);
                status.setText(message);
            }
        });
    }
    
    public FindInFilesDialog(Workspace workspace) {
        this.workspace = workspace;
    }

    /**
     * Sets the contents of the text field.
     * The value null causes the pattern to stay as it was.
     */
    public void setPattern(String pattern) {
        if (pattern == null) {
            return;
        }
        regexField.setText(pattern);
    }
    
    public void setFilenamePattern(String pattern) {
        filenameRegexField.setText(pattern);
    }
    
    public void showDialog() {
        if (workspace.isFileListUnsuitableFor("Find in Files")) {
            return;
        }
        
        initMatchList();
        
        // Register for notifications of files saved while our dialog is up.
        final SaveMonitor saveMonitor = SaveMonitor.getInstance();
        final SaveMonitor.Listener saveListener = new SaveMonitor.Listener() {
            /**
             * Update the matches if a file is saved. Ideally, we'd be a bit
             * more intelligent about this. I'd like to see a new UI that
             * doesn't build a tree and present it all at once; I'd like to
             * see matches as they're found.
             */
            public void fileSaved() {
                showMatches();
            }
        };
        saveMonitor.addSaveListener(saveListener);
        
        FormBuilder form = new FormBuilder(Edit.getInstance().getFrame(), "Find in Files");
        FormPanel formPanel = form.getFormPanel();
        formPanel.addRow("Files Containing:", regexField);
        formPanel.addRow("Whose Names Match:", filenameRegexField);
        formPanel.addRow("Matches:", new JScrollPane(matchView));
        formPanel.setStatusBar(status);
        formPanel.setTypingTimeoutActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                showMatches();
            }
        });
        // Remove our save listener when the dialog is dismissed.
        Runnable runnable = new Runnable() {
            public void run() {
                saveMonitor.removeSaveListener(saveListener);
            }
        };
        form.getFormDialog().setAcceptRunnable(runnable);
        form.getFormDialog().setCancelRunnable(runnable);
        form.showNonModal();
    }
}