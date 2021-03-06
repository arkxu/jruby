/*
 * Copyright (c) 2013 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.jruby.truffle.nodes.call;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.api.utilities.BranchProfile;
import org.jruby.common.IRubyWarnings;
import org.jruby.truffle.runtime.*;
import org.jruby.truffle.runtime.control.RaiseException;
import org.jruby.truffle.runtime.core.*;
import org.jruby.truffle.runtime.lookup.LookupNode;
import org.jruby.truffle.runtime.methods.*;

import java.util.HashMap;
import java.util.Map;

/**
 * A node in the dispatch chain that does no caching and looks up methods from scratch each time it
 * is called.
 */
public class GeneralDispatchNode extends BoxedDispatchNode {

    private final String name;
    private final Map<LookupNode, MethodCacheEntry> cache = new HashMap<>();
    @CompilerDirectives.CompilationFinal private boolean hasAnyMethodsMissing = false;

    public GeneralDispatchNode(RubyContext context, SourceSection sourceSection, String name) {
        super(context, sourceSection);
        assert name != null;
        this.name = name;
    }

    @Override
    public Object dispatch(VirtualFrame frame, RubyBasicObject receiverObject, RubyProc blockObject, Object[] argumentsObjects) {
        MethodCacheEntry entry = lookupInCache(receiverObject.getLookupNode());

        if (entry == null) {
            CompilerDirectives.transferToInterpreter();

            try {
                entry = new MethodCacheEntry(lookup(frame, receiverObject, name), false);
            } catch (UseMethodMissingException e) {
                try {
                    entry = new MethodCacheEntry(lookup(frame, receiverObject, "method_missing"), true);
                } catch (UseMethodMissingException e2) {
                    throw new RaiseException(getContext().getCoreLibrary().runtimeError(receiverObject.toString() + " didn't have a #method_missing"));
                }
            }

            if (entry.isMethodMissing()) {
                hasAnyMethodsMissing = true;
            }

            cache.put(receiverObject.getLookupNode(), entry);

            getContext().getRuntime().getWarnings().warn(IRubyWarnings.ID.TRUFFLE, getSourceSection().getSource().getName(), getSourceSection().getStartLine(), "general call node cache has grown to " + cache.size());
        }

        if (hasAnyMethodsMissing && entry.isMethodMissing()) {
            final Object[] modifiedArgumentsObjects = new Object[1 + argumentsObjects.length];
            modifiedArgumentsObjects[0] = getContext().newSymbol(name);
            System.arraycopy(argumentsObjects, 0, modifiedArgumentsObjects, 1, argumentsObjects.length);

            return entry.getMethod().call(frame.pack(), receiverObject, blockObject, modifiedArgumentsObjects);
        } else {
            return entry.getMethod().call(frame.pack(), receiverObject, blockObject, argumentsObjects);
        }
    }

    @CompilerDirectives.SlowPath
    public MethodCacheEntry lookupInCache(LookupNode lookupNode) {
        return cache.get(lookupNode);
    }

    private class MethodCacheEntry {

        private final RubyMethod method;
        private final boolean methodMissing;

        private MethodCacheEntry(RubyMethod method, boolean methodMissing) {
            assert method != null;
            this.method = method;
            this.methodMissing = methodMissing;
        }

        public RubyMethod getMethod() {
            return method;
        }

        public boolean isMethodMissing() {
            return methodMissing;
        }
    }

}
