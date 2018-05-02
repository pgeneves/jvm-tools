package analysis;

import analysis.util.TextTree;
import org.gridkit.jvmtool.heapdump.HeapHistogram;
import org.gridkit.jvmtool.heapdump.HeapWalker;
import org.netbeans.lib.profiler.heap.*;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.counting;

/**
 * This example demonstrates extracting JSF component trees 
 * from heap dump.
 * 
 * @author Alexey Ragozin (alexey.ragozin@gmail.com)
 */
public class CharArrayAnalysis {

    public static void main(String args[]) throws IOException {
        char[] tst = new char[5];
        System.out.println(tst.getClass());
        if (args.length != 1 || args[0].isEmpty()) {
            System.out.println("Usage: java -jar hprof-heap-0.9.4-SNAPSHOT-spring-boot.jar HEADUMP_FILE");
            System.exit(1);
        }
        String dumppath = args[0];
        new CharArrayAnalysis().check(dumppath);
    }

    /**
     * This entry point for this example.
     */
    public void check(String dumppath) throws FileNotFoundException, IOException {
        Heap heap = HeapFactory.createFastHeap(new File(dumppath));
        dumpComponentTree(heap);
    }
    
    /**
     * Print JSF component tree extracted from heap dump.
     */
    public void dumpComponentTree(Heap heap) {
        
        Set<JavaClass> compClasses = new HashSet<JavaClass>();
        Set<Instance> roots = new HashSet<Instance>();
        Map<Instance, List<Instance>> links = new HashMap<Instance, List<Instance>>();

        // Collect all subclasses of javax.faces.component.UIComponent 
        for(JavaClass jc: heap.getAllClasses()) {
           if (isComponent(jc)) {
               compClasses.add(jc);
           }
        }
        
        System.out.println("Char[] classes: " + compClasses.size());

        int total = 0;
        
        // Scan whole heap in search for UIComponent instances
        long maxSize=0;
        Instance maxInst = null;
        for(Instance i: heap.getAllInstances()) {
            if (!compClasses.contains(i.getJavaClass())) {
                continue;
            }
            ++total;

            if (maxSize < i.getSize()) {
                maxSize = i.getSize();
                maxInst = i;
            }

            if (total == 1) {
                for (FieldValue val : i.getFieldValues()) {
                    System.out.println(val.getField().getName() + " => " + val.getValue());
                }
            }

            // For each node find root and retain it in roots collection
            Instance v = HeapWalker.valueOf(i, "compositeParent");
            v = v != null ? v : HeapWalker.<Instance>valueOf(i, "parent");
            if (v == null) {
                roots.add(i);
            }
            else {
                // collect parent-to-child relations
                // as they are hard to extract 
                // from parent component instance
                if (!links.containsKey(v)) {
                    links.put(v, new ArrayList<Instance>());
                }
                links.get(v).add(i);
            }
        }

        System.out.println("maxsize "+maxSize+" at "+maxInst.getInstanceId());
        System.out.println("VALUE => "+limit(valueToString(HeapWalker.primitiveArrayValue(maxInst))));

        System.out.println("Found " + roots.size() + " component tree roots and " + total + " nodes in total");

        roots.stream()
                .filter(r -> r.getSize() > 1024)
                .map(r -> limit(valueToString(HeapWalker.primitiveArrayValue(r))))
                .filter(s -> s.startsWith("<response "))
                .limit(100).forEach(entry -> {
                    System.out.println(entry);
                });

        Map<String, Long> count = roots.stream()
                .filter(r -> r.getSize() > 1024)
                .map(r -> instanceEntry.from(valueToString(HeapWalker.primitiveArrayValue(r)), r.getSize()))
                .collect(Collectors.groupingBy(v -> v.getKey(), Collectors.summingLong(instanceEntry::getSize)));

        count.entrySet().stream().filter(entry -> entry.getValue() > 100).forEach(entry -> {
            System.out.println("SIZE "+ entry.getValue()+" FOR "+entry.getKey());
        });
    }

    private static String limit(String val, int len) {
        return val.substring(0, Math.min(val.length(), len));
    }

    private static String limit(String val) {
        return limit(val, 64);
    }

    private static String valueToString(Object value) {
        if (value instanceof char[]) {
            return new String((char[]) value);
        }
        return value.toString();
    }

    private void printTree(Instance root, Map<Instance, List<Instance>> links) {
        TextTree tree = tree(root, links);
        System.out.println(tree.printAsTree());
    }

    // TextTree is a helper class to output ASCII formated tree
    private TextTree tree(Instance node, Map<Instance, List<Instance>> links) {
        List<TextTree> c = new ArrayList<TextTree>();
        List<Instance> cc = links.get(node);
        if (cc != null) {
            for(Instance i: cc) {
                c.add(tree(i, links));
            }
        }
        return display(node, c.toArray(new TextTree[0]));
    }
    
    private TextTree display(Instance node, TextTree[] children) {
        String nodeType = simpleName(node.getJavaClass().getName());
        String info = "id:" + HeapWalker.valueOf(node, "id");
        String el = HeapWalker.valueOf(node, "txt.literal");
        if (el != null) {
            info += " el:" + el.replace('\n', ' ');
        }
        TextTree c = TextTree.t("#", children);
        
        return children.length == 0 
                    ? TextTree.t(nodeType, TextTree.t(info))
                    : TextTree.t(nodeType, TextTree.t(info), c);
    }

    private void collect(HeapHistogram h, Instance node, Map<Instance, List<Instance>> links) {
        h.feed(node);
        List<Instance> cc = links.get(node);
        if (cc != null) {
            for(Instance i: cc) {
                collect(h, i, links);
            }
        }
    }

    private String simpleName(String name) {
        int c = name.lastIndexOf('.');
        return c < 0 ? name : name.substring(c + 1);
    }

    public boolean isComponent(JavaClass type) {
        if (type.getName().equals("char[]")) {
            return true;
        }
        else if (type.getSuperClass() != null) {
            return isComponent(type.getSuperClass());
        }
        else {
            return false;
        }
    }    

    static class instanceEntry {
        private final String key;
        private final String val;
        private final long size;

        static instanceEntry from(String val, long size) {
            return new instanceEntry(limit(val, 10), limit(val, 64), size);
        }

        instanceEntry(String key, String val, long size) {
            this.key = key;
            this.val = val;
            this.size = size;
        }

        public String getKey() {
            return key;
        }

        public String getVal() {
            return val;
        }

        public long getSize() {
            return size;
        }
    }
}
