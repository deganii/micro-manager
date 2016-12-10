// Copyright (C) 2017 Open Imaging, Inc.
//
// LICENSE:      This file is distributed under the BSD license.
//               License text is included with the source distribution.
//
//               This file is distributed in the hope that it will be useful,
//               but WITHOUT ANY WARRANTY; without even the implied warranty
//               of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
//
//               IN NO EVENT SHALL THE COPYRIGHT OWNER OR
//               CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
//               INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES.

package org.micromanager.internal.utils;

import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;

/**
 * Our extended versions of some SwingUtilities methods.
 *
 * @author Mark A. Tsuchida
 */
public class MMSwingUtilities {
   // Guarded by monitor on MMSwingUtilities.class
   private static final Map<Class<?>, CoalescentRunnable> coalescedRunnables_ =
         new HashMap<Class<?>, CoalescentRunnable>();
   private static final Map<Class<?>, Long> skipCounts_ =
         new HashMap<Class<?>, Long>();

   /**
    * Invoke the given runnable on the EDT, coalescing multiple invocations on
    * the event queue.
    *
    * This is a mechanism to coalesce refresh-like tasks, in the manner of
    * Swing's {@code RepaintManager}, without having to replace the system
    * global event queue via {@EventQueue.push}.
    *
    * The given {@runnable} is scheduled to run on the EDT, just as with
    * {@code SwingUtilities.invokeLater}, but when invoked, all outstanding
    * runnables with the same "coalescence class"
    * ({@see CoalescentRunnable.getCoalescenceClass}) will be coalesced and the
    * result will be run.
    *
    * @param runnable the coalescent runnable to invoke on the EDT
    */
   public static void invokeLaterWithCoalescence(CoalescentRunnable runnable) {
      final Class<?> coalescenceClass = runnable.getCoalescenceClass();
      synchronized (MMSwingUtilities.class) {
         CoalescentRunnable coalesced =
               coalescedRunnables_.get(coalescenceClass);
         if (coalesced != null) {
            coalesced = coalesced.coalesceWith(runnable);
         }
         else {
            coalesced = runnable;
         }
         coalescedRunnables_.put(coalescenceClass, coalesced);
      }

      SwingUtilities.invokeLater(new Runnable() {
         @Override
         public void run() {
            final CoalescentRunnable coalesced;
            synchronized (MMSwingUtilities.class) {
               coalesced = coalescedRunnables_.remove(coalescenceClass);
            }
            if (coalesced == null) {
               return; // Already handled by previous invocations
            }

            coalesced.run();
         }
      });
   }

   /**
    * Like {@code invokeLaterWithCoalescence}, but defers invocation until the
    * last scheduled task on the event queue is processed.
    *
    * Note that if you keep calling this method at a higher rate than the EDT
    * is processing events, the runnable will never be executed.
    *
    * @param runnable the coalescent runnable to invoke on the EDT
    */
   public static void invokeAsLateAsPossibleWithCoalescence(
         CoalescentRunnable runnable)
   {
      final Class<?> coalescenceClass = runnable.getCoalescenceClass();
      synchronized (MMSwingUtilities.class) {
         CoalescentRunnable coalesced =
               coalescedRunnables_.get(coalescenceClass);
         if (coalesced != null) {
            coalesced = coalesced.coalesceWith(runnable);
            // Increment skip count
            Long oldSkipCount = skipCounts_.get(coalescenceClass);
            skipCounts_.put(coalescenceClass,
                  (oldSkipCount == null ? 0 : oldSkipCount) + 1);
         }
         else {
            coalesced = runnable;
         }
         coalescedRunnables_.put(coalescenceClass, coalesced);
      }

      SwingUtilities.invokeLater(new Runnable() {
         @Override
         public void run() {
            final CoalescentRunnable coalesced;
            synchronized (MMSwingUtilities.class) {
               Long skipCount = skipCounts_.get(coalescenceClass);
               if (skipCount != null && skipCount > 0) {
                  skipCounts_.put(coalescenceClass, skipCount - 1);
                  return;
               }
               coalesced = coalescedRunnables_.remove(coalescenceClass);
            }
            if (coalesced == null) {
               return; // Be defensive
            }

            coalesced.run();
         }
      });
   }

   /**
    * A "coalescent" runnable, for use with {@code invokeLaterWithCoalescence}.
    */
   public static interface CoalescentRunnable extends Runnable {
      /**
       * Return a tag class used to group instances that can be coalesced.
       * @return a tag class
       */
      Class<?> getCoalescenceClass();

      /**
       * Return a new runnable formed by coalescing this instance with another.
       *
       * It is guaranteed that {@code another} has the same coalescence class
       * as this instance, and that {@code another} was scheduled <i>after</i>
       * this instance (or the instances coalesced to form this instance).
       *
       * Note that this method should not have any side effects.
       *
       * @param another a newer coalescent runnable with which to coalesce
       * @return the coalesced runnable formed from this instance and {@code
       * another}
       */
      CoalescentRunnable coalesceWith(CoalescentRunnable another);
   }
}