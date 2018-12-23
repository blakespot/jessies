package e.edit;

import e.ptextarea.*;
import e.util.*;
import java.util.*;

/**
 * Looks things up in the Python documentation using pydoc(1).
 * We actually use our own wrapper round pydoc, to work around its limitations.
 * 
 * There are outstanding patches to pydoc that would improve its built-in web
 * server to the point where we might want to switch to launching that and
 * opening a real browser window. We'd gain things like an index and
 * documentation on keywords and topics, but we'd lose the links to the source
 * that we have at the moment, which are pretty cool. (Determined users could
 * tell their browser to use Evergreen for .py files, though.) Still, unless we
 * ship a patched pydoc.py, this is currently academic.
 */
public class PythonDocumentationResearcher implements WorkspaceResearcher {
    public String research(String string) {
        String pydocScript = Evergreen.getResourceFilename("lib", "scripts", "epydoc.py");
        
        ArrayList<String> lines = new ArrayList<>();
        ArrayList<String> errors = new ArrayList<>();
        int status = ProcessUtilities.backQuote(null, new String[] { pydocScript, string }, lines, errors);
        if (status == 1) {
            return "";
        }
        
        String result = StringUtilities.join(lines, "\n");
        
        // Rewrite links to point back to us, rather than to HTML files that don't exist.
        result = result.replaceAll("Help on (?:\\S+) (?:\\S+) in (\\S+):", "<p><code>import <a href=\"py:$1\">$1</code>");
        // FIXME: we should make the #anchors work, rather than quietly removing them.
        result = result.replaceAll("<a href=\"([^\"]+)\\.html(#[^\"]+)?\">", "<a href=\"py:$1\">");
        
        return result;
    }
    
    /** Returns true for Python files. */
    public boolean isSuitable(FileType fileType) {
        return fileType == FileType.PYTHON;
    }
    
    /** Handles our non-standard "py:" scheme. */
    public boolean handleLink(String link) {
        if (link.startsWith("py:")) {
            Advisor.getInstance().setDocumentationText(research(link.substring(3)));
            return true;
        }
        return false;
    }
}
