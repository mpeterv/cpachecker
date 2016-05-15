/*
 *  CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2014  Dirk Beyer
 *  All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 *  CPAchecker web page:
 *    http://cpachecker.sosy-lab.org
 */
package org.sosy_lab.cpachecker.util;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.collect.TreeTraverser;

import org.sosy_lab.cpachecker.core.interfaces.AbstractState;
import org.sosy_lab.cpachecker.core.interfaces.Precision;
import org.sosy_lab.cpachecker.core.interfaces.Property;
import org.sosy_lab.cpachecker.core.interfaces.WrapperPrecision;
import org.sosy_lab.cpachecker.core.reachedset.ReachedSet;
import org.sosy_lab.cpachecker.cpa.arg.ARGCPA;
import org.sosy_lab.cpachecker.cpa.automaton.AutomatonPrecision;
import org.sosy_lab.cpachecker.cpa.automaton.SafetyProperty;
import org.sosy_lab.cpachecker.cpa.composite.CompositePrecision;
import org.sosy_lab.cpachecker.util.presence.interfaces.PresenceCondition;
import org.sosy_lab.cpachecker.util.presence.interfaces.PresenceConditionManager;

import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

public class Precisions {

  private Precisions() { }

  /**
   * Retrieve one of the wrapped precisions by type. If the hierarchy of
   * (wrapped) precisions has several levels, this method searches through
   * them recursively.
   *
   * The type does not need to match exactly, the returned element has just to
   * be a sub-type of the type passed as argument.
   *
   * @param <T> The type of the wrapped element.
   * @param pType The class object of the type of the wrapped element.
   * @return An instance of an element with type T or null if there is none.
   */
  public static <T extends Precision> T extractPrecisionByType(Precision pPrecision, Class<T> pType) {
    if (pType.isInstance(pPrecision)) {
      return pType.cast(pPrecision);

    } else if (pPrecision instanceof WrapperPrecision) {
      return ((WrapperPrecision)pPrecision).retrieveWrappedPrecision(pType);
    }

    return null;
  }

  /**
   * Creates a {@link FluentIterable} that enumerates all the <code>Precision</code>
   * contained in a given precision pre-order. The root precision itself is included, the
   * precisions are unwrapped recursively.
   *
   * <p><b>Example</b>: Precision A wraps precisions B and C. Precision B wraps precisions D and E.<br />
   *             The resulting tree (see below) is traversed pre-order.
   * <pre>
   *                  A
   *                 / \
   *                B   C
   *               / \
   *              D   E
   * </pre>
   * The returned <code>FluentIterable</code> iterates over the items in the following
   * order : A, B, D, E, C.
   * </p>
   *
   * @param prec the root precision
   *
   * @return a <code>FluentIterable</code> over the given root precision and all precisions
   *         that are wrapped in it, recursively
   */
  public static FluentIterable<Precision> asIterable(final Precision prec) {

    return new TreeTraverser<Precision>() {

      @Override
      public Iterable<Precision> children(Precision precision) {
        if (precision instanceof WrapperPrecision) {
          return ((WrapperPrecision)precision).getWrappedPrecisions();
        }

        return ImmutableList.of();
      }
    }.preOrderTraversal(prec);
  }

  public static Precision replaceByType(Precision pOldPrecision, Precision pNewPrecision, Predicate<? super Precision> pPrecisionType) {
    if (pOldPrecision instanceof WrapperPrecision) {
      return ((WrapperPrecision)pOldPrecision).replaceWrappedPrecision(pNewPrecision, pPrecisionType);
    } else {
      assert pNewPrecision.getClass().isAssignableFrom(pOldPrecision.getClass());

      return pNewPrecision;
    }
  }

  public static Precision replaceByFunction(Precision pOldPrecision, Function<Precision, Precision> pFunction) {

    if (pOldPrecision instanceof CompositePrecision) {
      return ((CompositePrecision) pOldPrecision).replacePrecision(pFunction);
    }

    Precision result = pFunction.apply(pOldPrecision);

    if (result == pOldPrecision ) {
      return null;
    }

    return result;
  }

  public static void updatePropertyBlacklistOnWaitlist(ARGCPA pCpa, final ReachedSet pReachedSet, final Set<Property> pToBlacklist) {

    final HashSet<SafetyProperty> toBlacklist = Sets.newHashSet(
      Collections2.transform(pToBlacklist, new Function<Property, SafetyProperty>() {
        @Override
        public SafetyProperty apply(Property pProp) {
          Preconditions.checkArgument(pProp instanceof SafetyProperty);
          return (SafetyProperty) pProp;
        }

      }).iterator());

    // update the precision:
    //  (optional) disable some automata transitions (global precision)
    for (AbstractState e: pReachedSet.getWaitlist()) {

      final Precision pi = pReachedSet.getPrecision(e);
      final Precision piPrime = withPropertyBlacklist(pi, toBlacklist);

      if (piPrime != null) {
        pReachedSet.updatePrecision(e, piPrime);
      }
    }

    for (Property p: pToBlacklist) {
      pCpa.getCexSummary().signalPropertyDisabled(p);
    }
  }

  public static Precision withPropertyBlacklist(final Precision pi, final HashSet<SafetyProperty> toBlacklist) {
    final Map<SafetyProperty, Optional<PresenceCondition>> blacklist = Maps.asMap(toBlacklist, new Function<SafetyProperty, Optional<PresenceCondition>>() {
      @Override
      public Optional<PresenceCondition> apply(SafetyProperty pArg0) {
        return Optional.absent();
      }
    });


    final Precision piPrime = Precisions.replaceByFunction(pi, new Function<Precision, Precision>() {
      @Override
      public Precision apply(Precision pPrecision) {
        if (pPrecision instanceof AutomatonPrecision) {
          return AutomatonPrecision.initBlacklist(blacklist, null);
        }
        return null;
      }
    });
    return piPrime;
  }

  public static void disablePropertiesForWaitlist(ARGCPA pCpa, final ReachedSet pReachedSet,
      final Map<Property, Optional<PresenceCondition>> pToBlacklist,
      final PresenceConditionManager pRegionManager) {

    Map<SafetyProperty, Optional<PresenceCondition>> toBlackList = Maps.newHashMap();
    for (Entry<Property, Optional<PresenceCondition>> e: pToBlacklist.entrySet()) {
      toBlackList.put((SafetyProperty)e.getKey(), e.getValue());
    }

    // update the precision:
    //  (optional) disable some automata transitions (global precision)
    for (AbstractState e: pReachedSet.getWaitlist()) {

      final Precision pi = pReachedSet.getPrecision(e);
      final Precision piPrime = blacklistProperties(pi, toBlackList, pRegionManager);

      if (piPrime != null) {
        pReachedSet.updatePrecision(e, piPrime);
      }
    }

  }

  public static Precision blacklistProperties(final Precision pi,
      final Map<SafetyProperty, Optional<PresenceCondition>> toBlacklist,
      final PresenceConditionManager pRegionManager) {
    final Precision piPrime = Precisions.replaceByFunction(pi, new Function<Precision, Precision>() {
      @Override
      public Precision apply(Precision pPrecision) {
        if (pPrecision instanceof AutomatonPrecision) {
          AutomatonPrecision pi = (AutomatonPrecision) pPrecision;
          return pi.cloneAndAddBlacklisted(toBlacklist, pRegionManager);
        }
        return null;
      }
    });
    return piPrime;
  }

}
