package datawave.query.iterator.logic;

import com.google.common.collect.TreeMultimap;
import datawave.query.attributes.Document;
import datawave.query.iterator.NestedIterator;
import datawave.query.iterator.SeekableIterator;
import datawave.query.iterator.Util;
import datawave.query.iterator.Util.Transformer;
import org.apache.accumulo.core.data.ByteSequence;
import org.apache.accumulo.core.data.Range;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;

/**
 * Performs a merge join of the child iterators. It is expected that all child iterators return values in sorted order.
 */
public class AndIterator<T extends Comparable<T>> implements NestedIterator<T>, SeekableIterator {
    // temporary stores of uninitialized streams of iterators
    private List<NestedIterator<T>> includes, excludes, contextIncludes, contextExcludes;
    
    private Map<T,T> transforms;
    private Transformer<T> transformer;
    
    private TreeMultimap<T,NestedIterator<T>> includeHeads, excludeHeads, contextIncludeHeads, contextExcludeHeads, contextIncludeNullHeads,
                    contextExcludeNullHeads;
    private T prev;
    private T next;
    
    private Document prevDocument, document;
    private T evaluationContext;
    
    private static final Logger log = Logger.getLogger(AndIterator.class);
    
    public AndIterator(Iterable<NestedIterator<T>> sources) {
        this(sources, null);
    }
    
    public AndIterator(Iterable<NestedIterator<T>> sources, Iterable<NestedIterator<T>> filters) {
        includes = new LinkedList<>();
        contextIncludes = new LinkedList<>();
        for (NestedIterator<T> src : sources) {
            if (src.isContextRequired()) {
                contextIncludes.add(src);
            } else {
                includes.add(src);
            }
        }
        
        if (filters == null) {
            excludes = Collections.emptyList();
            contextExcludes = Collections.emptyList();
        } else {
            excludes = new LinkedList<>();
            contextExcludes = new LinkedList<>();
            for (NestedIterator<T> filter : filters) {
                if (filter.isContextRequired()) {
                    contextExcludes.add(filter);
                } else {
                    excludes.add(filter);
                }
            }
        }
    }
    
    public void initialize() {
        Comparator<T> keyComp = Util.keyComparator();
        // nestedIteratorComparator will keep a deterministic ordering, unlike hashCodeComparator
        Comparator<NestedIterator<T>> itrComp = Util.nestedIteratorComparator();
        
        transformer = Util.keyTransformer();
        transforms = new HashMap<>();
        
        includeHeads = TreeMultimap.create(keyComp, itrComp);
        includeHeads = initSubtree(includeHeads, includes, transformer, transforms, true);
        
        if (excludes.isEmpty()) {
            excludeHeads = Util.getEmpty();
        } else {
            excludeHeads = TreeMultimap.create(keyComp, itrComp);
            // pass null in for transforms as excludes are not returned
            excludeHeads = initSubtree(excludeHeads, excludes, transformer, null, false);
        }
        
        if (!contextIncludes.isEmpty()) {
            contextIncludeHeads = TreeMultimap.create(keyComp, itrComp);
            contextIncludeNullHeads = TreeMultimap.create(keyComp, itrComp);
        }
        
        if (contextExcludes != null && !contextExcludes.isEmpty()) {
            contextExcludeHeads = TreeMultimap.create(keyComp, itrComp);
            contextExcludeNullHeads = TreeMultimap.create(keyComp, itrComp);
        }
        
        next();
    }
    
    public boolean isInitialized() {
        return includeHeads != null;
    }
    
    /**
     * Apply a candidate as a context against both contextIncludes and contextExcludes.
     *
     * @param candidate
     *            to be used as context against contextIncludes and contextExcludes
     * @return true if candidate is included in all contextIncludes and excluded in all contextExcludes, false otherwise
     */
    private boolean applyContextRequired(T candidate) {
        if (contextIncludes.size() > 0) {
            T highestContextInclude = NestedIteratorContextUtil
                            .intersect(candidate, contextIncludes, contextIncludeHeads, contextIncludeNullHeads, transformer);
            // if there wasn't an intersection here move to the next one
            if (!candidate.equals(highestContextInclude)) {
                if (highestContextInclude != null) {
                    // move to the next highest key
                    includeHeads = moveIterators(candidate, highestContextInclude);
                    return false;
                } else {
                    // all we know is they didn't intersect advance to next
                    includeHeads = advanceIterators(candidate);
                    return false;
                }
            }
        }
        
        // test any contextExcludes against candidate
        if (contextExcludes.size() > 0) {
            // DeMorgans Law: (~A) AND (~B) == ~(A OR B)
            // for an exclude union lowest with the set
            T unionExclude = NestedIteratorContextUtil.union(candidate, contextExcludes, contextExcludeHeads, contextExcludeNullHeads, transformer);
            // if the union matched it is not a hit
            if (candidate.equals(unionExclude)) {
                // advance and try again
                includeHeads = advanceIterators(candidate);
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * return the previously found next and set its document. If there are more head references, advance until the lowest and highest match that is not
     * filtered, advancing all iterators tied to lowest and set next/document for the next call
     * 
     * @return the previously found next
     */
    public T next() {
        if (!isInitialized()) {
            throw new IllegalStateException("initialize() was never called");
        }
        if (isContextRequired() && evaluationContext == null) {
            throw new IllegalStateException("evaluationContext must be set prior to calling next");
        }
        
        prev = next;
        prevDocument = document;
        
        // look through includes for candidates if there are any
        while (!includeHeads.isEmpty()) {
            SortedSet<T> topKeys = includeHeads.keySet();
            T lowest = topKeys.first();
            T highest = topKeys.last();
            
            // short circuit if possible from a supplied evaluation context
            if (evaluationContext != null) {
                int lowestCompare = lowest.compareTo(evaluationContext);
                int highestCompare = highest.compareTo(evaluationContext);
                
                if (lowestCompare > 0 || highestCompare > 0) {
                    // if any value is beyond the evaluationContext it's not possible to intersect
                    break;
                }
                
                // advance anything less than the evaluation context to the evaluation context
                SortedSet<T> toMove = topKeys.headSet(evaluationContext);
                if (!toMove.isEmpty()) {
                    includeHeads = moveIterators(toMove, evaluationContext);
                    continue;
                }
            }
            
            // if the highest and lowest are the same we are currently intersecting
            if (lowest.equals(highest)) {
                // make sure this value isn't filtered
                if (!NegationFilter.isFiltered(lowest, excludeHeads, transformer)) {
                    // use this value as a candidate against any includes/excludes that require context
                    if (applyContextRequired(lowest)) {
                        // found a match, set next/document and advance
                        next = transforms.get(lowest);
                        document = Util.buildNewDocument(includeHeads.values());
                        includeHeads = advanceIterators(lowest);
                        break;
                    }
                } else {
                    // filtered, advance the iterators (which are all currently pointing at the same point)
                    includeHeads = advanceIterators(lowest);
                }
            } else {
                // haven't converged yet, take the next highest and move it
                T nextHighest = topKeys.headSet(highest).last();
                includeHeads = moveIterators(nextHighest, highest);
            }
        }
        
        // for cases where there are no sources the only source for a candidate is the evaluationContext.
        if (isContextRequired()) {
            // test exclude for the candidate in case there are excludes
            if (!NegationFilter.isFiltered(evaluationContext, excludeHeads, transformer)) {
                if (applyContextRequired(evaluationContext)) {
                    next = evaluationContext;
                    document = Util.buildNewDocument(Collections.emptyList());
                }
            }
        }
        
        // if we didn't move after the loop, then we don't have a next after this
        if (prev == next) {
            next = null;
        }
        
        return prev;
    }
    
    public void remove() {
        throw new UnsupportedOperationException("This iterator does not support remove.");
    }
    
    public boolean hasNext() {
        if (!isInitialized()) {
            throw new IllegalStateException("initialize() was never called");
        }
        
        return next != null;
    }
    
    @Override
    public void seek(Range range, Collection<ByteSequence> columnFamilies, boolean inclusive) throws IOException {
        // seek all of the iterators. Drop those that fail, as long as we have at least one include left
        Iterator<NestedIterator<T>> include = includes.iterator();
        while (include.hasNext()) {
            NestedIterator<T> child = include.next();
            try {
                for (NestedIterator<T> itr : child.leaves()) {
                    if (itr instanceof SeekableIterator) {
                        ((SeekableIterator) itr).seek(range, columnFamilies, inclusive);
                    }
                }
            } catch (Exception e) {
                include.remove();
                if (includes.isEmpty()) {
                    throw e;
                } else {
                    log.warn("Failed include lookup, but dropping in lieu of other terms", e);
                }
                
            }
        }
        Iterator<NestedIterator<T>> exclude = excludes.iterator();
        while (exclude.hasNext()) {
            NestedIterator<T> child = exclude.next();
            for (NestedIterator<T> itr : child.leaves()) {
                if (itr instanceof SeekableIterator) {
                    ((SeekableIterator) itr).seek(range, columnFamilies, inclusive);
                }
            }
        }
        
        if (isInitialized()) {
            // advance throwing next away and re-populating next with what should be
            next();
        }
    }
    
    /**
     * Test all layers of cache for the minimum, then if necessary advance heads
     *
     * @param minimum
     *            the minimum to return
     * @return the first greater than or equal to minimum or null if none exists
     * @throws IllegalStateException
     *             if prev is greater than or equal to minimum
     */
    public T move(T minimum) {
        if (!isInitialized()) {
            throw new IllegalStateException("initialize() was never called");
        }
        
        if (prev != null && prev.compareTo(minimum) >= 0) {
            throw new IllegalStateException("Tried to call move when already at or beyond move point: topkey=" + prev + ", movekey=" + minimum);
        }
        
        // test if the cached next is already beyond the minimum
        if (next != null && next.compareTo(minimum) >= 0) {
            // simply advance to next
            return next();
        }
        
        SortedSet<T> headSet = includeHeads.keySet().headSet(minimum);
        includeHeads = moveIterators(headSet, minimum);
        
        // next < minimum, so advance throwing next away and re-populating next with what should be >= minimum
        next();
        
        // now as long as the newly computed next exists return it and advance
        if (hasNext()) {
            return next();
        } else {
            includeHeads = Util.getEmpty();
            return null;
        }
    }
    
    public Collection<NestedIterator<T>> leaves() {
        LinkedList<NestedIterator<T>> leaves = new LinkedList<>();
        // treat this node as a leaf to allow us to pass through the seek method and appropriately drop branches if possible.
        leaves.add(this);
        return leaves;
    }
    
    @Override
    public Collection<NestedIterator<T>> children() {
        ArrayList<NestedIterator<T>> children = new ArrayList<>(includes.size() + excludes.size() + contextIncludes.size() + contextExcludes.size());
        
        children.addAll(includes);
        children.addAll(excludes);
        children.addAll(contextIncludes);
        children.addAll(contextExcludes);
        
        return children;
    }
    
    /**
     * Advances all iterators associated with the supplied key and adds them back into the sorted multimap. If any of the sub-trees returns false, this method
     * immediately returns false to indicate that a sub-tree has been exhausted.
     *
     * @param key
     * @return
     */
    protected TreeMultimap<T,NestedIterator<T>> advanceIterators(T key) {
        T highest = null;
        transforms.remove(key);
        for (NestedIterator<T> itr : includeHeads.removeAll(key)) {
            T next;
            try {
                // if there is already a known highest go straight there instead of next
                if (highest != null) {
                    next = itr.move(highest);
                } else if (itr.hasNext()) {
                    next = itr.next();
                } else {
                    return Util.getEmpty();
                }
                
                if (next == null) {
                    return Util.getEmpty();
                }
                
                T transform = transformer.transform(next);
                transforms.put(transform, next);
                includeHeads.put(transform, itr);
                
                // move the highest if the new key is higher than the current key and the highest seen so far
                if ((highest == null && transform.compareTo(key) > 0) || (highest != null && transform.compareTo(highest) > 0)) {
                    highest = transform;
                }
            } catch (Exception e) {
                // only need to actually fail if we have nothing left in the AND clause
                if (includeHeads.isEmpty()) {
                    throw e;
                } else {
                    log.warn("Failed include lookup, but dropping in lieu of other terms", e);
                }
            }
        }
        return includeHeads;
    }
    
    /**
     * Similar to <code>advanceIterators</code>, but instead of calling <code>next</code> on each sub-tree, this calls <code>move</code> with the supplied
     * <code>to</code> parameter.
     *
     * @param key
     * @param to
     * @return
     */
    protected TreeMultimap<T,NestedIterator<T>> moveIterators(T key, T to) {
        transforms.remove(key);
        for (NestedIterator<T> itr : includeHeads.removeAll(key)) {
            T next = itr.move(to);
            if (next == null) {
                return Util.getEmpty();
            } else {
                T transform = transformer.transform(next);
                transforms.put(transform, next);
                includeHeads.put(transform, itr);
                
                if (transform.compareTo(to) > 0) {
                    to = transform;
                }
            }
        }
        return includeHeads;
    }
    
    protected TreeMultimap<T,NestedIterator<T>> moveIterators(SortedSet<T> toMove, T to) {
        T highest = null;
        
        // get the highest key of toMove since it is likely to be the lowest cardinality
        if (!toMove.isEmpty()) {
            highest = toMove.last();
        }
        
        while (highest != null && !includeHeads.isEmpty()) {
            // if include heads is higher than the target key, adjust the target key to the highest since an intersection is not possible at anything but the
            // highest
            T highestInternal = includeHeads.keySet().last();
            if (highestInternal.compareTo(to) > 0) {
                to = highestInternal;
            }
            
            // advance the iterators
            includeHeads = moveIterators(highest, to);
            
            // get the next highest key in the set to move
            toMove = toMove.headSet(highest);
            if (!toMove.isEmpty()) {
                highest = toMove.last();
            } else {
                highest = null;
            }
        }
        
        return includeHeads;
    }
    
    /**
     * Creates a sorted mapping of values to iterators.
     *
     * @param subtree
     * @param sources
     * @return
     */
    private static <T extends Comparable<T>> TreeMultimap<T,NestedIterator<T>> initSubtree(TreeMultimap<T,NestedIterator<T>> subtree,
                    Iterable<NestedIterator<T>> sources, Transformer<T> transformer, Map<T,T> transforms, boolean anded) {
        for (NestedIterator<T> src : sources) {
            src.initialize();
            if (src.hasNext()) {
                T next = src.next();
                T transform = transformer.transform(next);
                if (transforms != null) {
                    transforms.put(transform, next);
                }
                subtree.put(transform, src);
            } else if (anded) {
                // If a source has no valid records, it shouldn't throw an exception. It should just return no results.
                // For an And, once one source is exhausted, the entire tree is exhausted
                return Util.getEmpty();
            }
        }
        return subtree;
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("AndIterator: ");
        
        sb.append("Includes: ");
        sb.append(includes);
        sb.append(", Deferred Includes: ");
        sb.append(contextIncludes);
        sb.append(", Excludes: ");
        sb.append(excludes);
        sb.append(", Deferred Excludes: ");
        sb.append(contextExcludes);
        
        return sb.toString();
    }
    
    public Document document() {
        return prevDocument;
    }
    
    /**
     * As long as there is at least one sourced included no context is required
     * 
     * @return true if there are no includes, false otherwise
     */
    @Override
    public boolean isContextRequired() {
        return includes.isEmpty();
    }
    
    /**
     * This context will be used even if isContextRequired is false as an anchor point for highest/lowest during next calls
     * 
     * @param context
     */
    @Override
    public void setContext(T context) {
        this.evaluationContext = context;
    }
}
