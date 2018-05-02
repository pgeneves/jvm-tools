package analysis;

import analysis.util.TextTree;
import org.gridkit.jvmtool.heapdump.HeapHistogram;
import org.gridkit.jvmtool.heapdump.HeapWalker;
import org.netbeans.lib.profiler.heap.*;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;
import java.util.function.Function;
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
            System.err.println("Please provide heapdump path as sole argument");
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
            System.out.println("class => "+jc.getName());
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

        Map<String, Long> count = roots.stream().map(r -> limit(valueToString(HeapWalker.primitiveArrayValue(r))))
                .collect(Collectors.groupingBy(Function.identity(), counting()));

        count.entrySet().stream().filter(entry -> entry.getValue() > 100).forEach(entry -> {
            System.out.println(entry.getValue()+" => "+entry.getKey());
        });

//        for (Instance root: roots) {
//            System.out.println(limit(valueToString(HeapWalker.primitiveArrayValue(maxInst))));
//        }

        
//        // Report tree for each root UIComponent found before
//        for(Instance root: roots) {
//            HeapHistogram hh = new HeapHistogram();
//            // links variable contains all edges in component graphs identified during heap scan
//            collect(hh, root, links);
//            System.out.println();
//            System.out.println(root.getInstanceId());
//            System.out.println(hh.formatTop(10));
//            System.out.println();
//            // Dump may contain partial trees
//            // Report only reasonably large object clusters
//            if (hh.getTotalCount() > 500) {
//                printTree(root, links);
//                break;
//            }
//        }
    }

    private static String limit(String val) {
        return val.substring(0, Math.min(val.length(), 64));
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
}
