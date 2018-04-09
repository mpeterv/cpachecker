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
package org.sosy_lab.cpachecker.cpa.pointer2;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import org.sosy_lab.common.collect.PathCopyingPersistentTreeMap;
import org.sosy_lab.common.collect.PersistentSortedMap;
import org.sosy_lab.cpachecker.core.interfaces.AbstractState;
import org.sosy_lab.cpachecker.cpa.pointer2.util.ExplicitLocationSet;
import org.sosy_lab.cpachecker.cpa.pointer2.util.LocationSet;
import org.sosy_lab.cpachecker.cpa.pointer2.util.LocationSetBot;
import org.sosy_lab.cpachecker.cpa.pointer2.util.LocationSetTop;
import org.sosy_lab.cpachecker.util.refinement.ForgetfulState;
import org.sosy_lab.cpachecker.util.states.MemoryLocation;

import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.Iterables;

/**
 * Instances of this class are pointer states that are used as abstract elements
 * in the pointer CPA.
 */
public class PointerState implements AbstractState, ForgetfulState<PointerInformation> {

  /**
   * The initial empty pointer state.
   */
  public static final PointerState INITIAL_STATE = new PointerState();

  /**
   * The points-to map of the state.
   */
  private PersistentSortedMap<MemoryLocation, LocationSet> pointsToMap;

  /**
   * Creates a new pointer state with an empty initial points-to map.
    */
  private PointerState() {
    pointsToMap = PathCopyingPersistentTreeMap.of();
  }

  /**
   * Creates a new pointer state from the given persistent points-to map.
   *
   * @param pPointsToMap the points-to map of this state.
   */
  private PointerState(PersistentSortedMap<MemoryLocation, LocationSet> pPointsToMap) {
    this.pointsToMap = pPointsToMap;
  }

  /**
   * Gets a pointer state representing the points to information of this state
   * combined with the information that the first given identifier points to the
   * second given identifier.
   *
   * @param pSource the first identifier.
   * @param pTarget the second identifier.
   * @return the pointer state.
   */
  public PointerState addPointsToInformation(MemoryLocation pSource, MemoryLocation pTarget) {
    LocationSet previousPointsToSet = getPointsToSet(pSource);
    LocationSet newPointsToSet = previousPointsToSet.addElement(pTarget);
    return new PointerState(pointsToMap.putAndCopy(pSource, newPointsToSet));
  }

  /**
   * Gets a pointer state representing the points to information of this state
   * combined with the information that the first given identifier points to the
   * given target identifiers.
   *
   * @param pSource the first identifier.
   * @param pTargets the target identifiers.
   * @return the pointer state.
   */
  public PointerState addPointsToInformation(MemoryLocation pSource, Iterable<MemoryLocation> pTargets) {
    LocationSet previousPointsToSet = getPointsToSet(pSource);
    LocationSet newPointsToSet = previousPointsToSet.addElements(pTargets);
    return new PointerState(pointsToMap.putAndCopy(pSource, newPointsToSet));
  }

  /**
   * Gets a pointer state representing the points to information of this state
   * combined with the information that the first given identifier points to the
   * given target identifiers.
   *
   * @param pSource the first identifier.
   * @param pTargets the target identifiers.
   * @return the pointer state.
   */
  public PointerState addPointsToInformation(MemoryLocation pSource, LocationSet pTargets) {
    if (pTargets.isBot()) {
      return this;
    }
    if (pTargets.isTop()) {
      return new PointerState(pointsToMap.putAndCopy(pSource, LocationSetTop.INSTANCE));
    }
    LocationSet previousPointsToSet = getPointsToSet(pSource);
    return new PointerState(pointsToMap.putAndCopy(pSource, previousPointsToSet.addElements(pTargets)));
  }

  /**
   * Gets the points-to set mapped to the given identifier.
   *
   * @param pSource the identifier pointing to the points-to set in question.
   * @return the points-to set of the given identifier.
   */
  public LocationSet getPointsToSet(MemoryLocation pSource) {
    LocationSet result = this.pointsToMap.get(pSource);
    if (result == null) {
      return LocationSetBot.INSTANCE;
    }
    return result;
  }

  /**
   * Checks whether or not the first identifier points to the second identifier.
   *
   * @param pSource the first identifier.
   * @param pTarget the second identifier.
   * @return <code>true</code> if the first identifier definitely points to the
   * second identifier, <code>false</code> if it definitely does not point to
   * the second identifier and <code>null</code> if it might point to it.
   */
  @Nullable
  public Boolean pointsTo(MemoryLocation pSource, MemoryLocation pTarget) {
    LocationSet pointsToSet = getPointsToSet(pSource);
    if (pointsToSet.equals(LocationSetBot.INSTANCE)) {
      return false;
    }
    if (pointsToSet instanceof ExplicitLocationSet) {
      ExplicitLocationSet explicitLocationSet = (ExplicitLocationSet) pointsToSet;
      if (explicitLocationSet.mayPointTo(pTarget)) {
        return explicitLocationSet.getSize() == 1 ? true : null;
      } else {
        return false;
      }
    }
    return null;
  }

  /**
   * Checks whether or not the first identifier is known to point to the second
   * identifier.
   *
   * @return <code>true</code> if the first identifier definitely points to the
   * second identifier, <code>false</code> if it might point to it or is known
   * not to point to it.
   */
  public boolean definitelyPointsTo(MemoryLocation pSource, MemoryLocation pTarget) {
    return Boolean.TRUE.equals(pointsTo(pSource, pTarget));
  }

  /**
   * Checks whether or not the first identifier is known to not point to the
   * second identifier.
   *
   * @return <code>true</code> if the first identifier definitely does not
   * points to the second identifier, <code>false</code> if it might point to
   * it or is known to point to it.
   */
  public boolean definitelyNotPointsTo(MemoryLocation pSource, MemoryLocation pTarget) {
    return Boolean.FALSE.equals(pointsTo(pSource, pTarget));
  }

  /**
   * Checks whether or not the first identifier is may point to the second
   * identifier.
   *
   * @return <code>true</code> if the first identifier definitely points to the
   * second identifier or might point to it, <code>false</code> if it is known
   * not to point to it.
   */
  public boolean mayPointTo(MemoryLocation pSource, MemoryLocation pTarget) {
    return !Boolean.FALSE.equals(pointsTo(pSource, pTarget));
  }

  /**
   * Gets all locations known to the state.
   *
   * @return all locations known to the state.
   */
  public Set<MemoryLocation> getKnownLocations() {
    return FluentIterable.from(Iterables.concat(pointsToMap.keySet(), FluentIterable.from(pointsToMap.values()).transformAndConcat(new Function<LocationSet, Iterable<? extends MemoryLocation>>() {

      @Override
      public Iterable<? extends MemoryLocation> apply(LocationSet pArg0) {
        if (pArg0 instanceof ExplicitLocationSet) {
          return (ExplicitLocationSet) pArg0;
        }
        return Collections.emptySet();
      }

    }))).toSet();
  }

  /**
   * Gets the points-to map of this state.
   *
   * @return the points-to map of this state.
   */
  public Map<MemoryLocation, LocationSet> getPointsToMap() {
    return Collections.unmodifiableMap(this.pointsToMap);
  }

  @Override
  public boolean equals(Object pO) {
    if (this == pO) {
      return true;
    }
    if (pO instanceof PointerState) {
      return pointsToMap.equals(((PointerState) pO).pointsToMap);
    }
    return false;
  }

  @Override
  public int hashCode() {
    return pointsToMap.hashCode();
  }

  @Override
  public String toString() {
    return pointsToMap.toString();
  }

  public static PointerState copyOf(PointerState pState) {
    return new PointerState(PathCopyingPersistentTreeMap.copyOf(pState.pointsToMap));
  }

  @Override
  public PointerInformation forget(MemoryLocation pPtr) {
    pointsToMap = pointsToMap.removeAndCopy(pPtr);
    // TODO: if it is needed - PointerInformation has empty implementation
    return null;
  }

  @Override
  public void remember(MemoryLocation location, PointerInformation forgottenInformation) {
    // TODO: if it is needed - PointerInformation has empty implementation
  }

  @Override
  public Set<MemoryLocation> getTrackedMemoryLocations() {
    return pointsToMap.keySet();
  }

  public static boolean isFictionalPointer(MemoryLocation ptr) {
    return ptr.getIdentifier().contains("##");
  }

  @Override
  public int getSize() {
    return pointsToMap.size();
  }
}
