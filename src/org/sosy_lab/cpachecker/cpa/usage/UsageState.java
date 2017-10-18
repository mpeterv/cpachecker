/*
 *  CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2012  Dirk Beyer
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
package org.sosy_lab.cpachecker.cpa.usage;

import static com.google.common.collect.FluentIterable.from;

import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import java.io.PrintStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.sosy_lab.common.time.Timer;
import org.sosy_lab.cpachecker.core.defaults.AbstractSingleWrapperState;
import org.sosy_lab.cpachecker.core.interfaces.AbstractState;
import org.sosy_lab.cpachecker.cpa.arg.ARGState;
import org.sosy_lab.cpachecker.cpa.lock.LockState;
import org.sosy_lab.cpachecker.cpa.lock.effects.LockEffect;
import org.sosy_lab.cpachecker.cpa.predicate.PredicateAbstractState;
import org.sosy_lab.cpachecker.cpa.usage.storage.FunctionContainer;
import org.sosy_lab.cpachecker.cpa.usage.storage.FunctionContainer.StorageStatistics;
import org.sosy_lab.cpachecker.cpa.usage.storage.TemporaryUsageStorage;
import org.sosy_lab.cpachecker.cpa.usage.storage.UsageContainer;
import org.sosy_lab.cpachecker.util.AbstractStates;
import org.sosy_lab.cpachecker.util.identifiers.AbstractIdentifier;
import org.sosy_lab.cpachecker.util.identifiers.Identifiers;
import org.sosy_lab.cpachecker.util.identifiers.SingleIdentifier;

/**
 * Represents one abstract state of the Usage CPA.
 */
public class UsageState extends AbstractSingleWrapperState {
  /* Boilerplate code to avoid serializing this class */

  private static final long serialVersionUID = -898577877284268426L;
  private TemporaryUsageStorage recentUsages;
  private boolean isStorageCloned;
  private final UsageContainer globalContainer;
  private final FunctionContainer functionContainer;
  private final StateStatistics stats;

  private boolean isExitState;

  private final Map<AbstractIdentifier, AbstractIdentifier> variableBindingRelation;

  private UsageState(final AbstractState pWrappedElement
      , final Map<AbstractIdentifier, AbstractIdentifier> pVarBind
      , final TemporaryUsageStorage pRecentUsages
      , final UsageContainer pContainer
      , final boolean pCloned
      , final FunctionContainer pFuncContainer
      , final StateStatistics pStats
      , boolean exit) {
    super(pWrappedElement);
    variableBindingRelation = pVarBind;
    recentUsages = pRecentUsages;
    globalContainer = pContainer;
    isStorageCloned = pCloned;
    functionContainer = pFuncContainer;
    stats = pStats;
    isExitState = exit;
  }

  public static UsageState createInitialState(final AbstractState pWrappedElement
      , final UsageContainer pContainer) {
    FunctionContainer initialContainer = FunctionContainer.createInitialContainer();
    return new UsageState(pWrappedElement, new HashMap<>(), new TemporaryUsageStorage(),
        pContainer, true, initialContainer, new StateStatistics(initialContainer.getStatistics()), false);
  }

  private UsageState(final AbstractState pWrappedElement, final UsageState state) {
    this(pWrappedElement, new HashMap<>(state.variableBindingRelation), state.recentUsages,
        state.globalContainer, false, state.functionContainer, state.stats, state.isExitState);
  }

  public boolean containsLinks(final AbstractIdentifier id) {
    /* Special contains!
    *  if we have *b, map also contains **b, ***b and so on.
    *  So, if we get **b, having (*b, c), we give *c
    */
    if (from(Identifiers.getDereferencedIdentifiers(id))
        .anyMatch(i -> variableBindingRelation.containsKey(i))) {
      return true;
    }
    return false;
  }

  public void put(final AbstractIdentifier id1, final AbstractIdentifier id2) {
    if (!id1.equals(id2)) {
      variableBindingRelation.put(id1, id2);
    }
  }

  public boolean containsUsage(final SingleIdentifier id) {
    return recentUsages.containsKey(id);
  }

  public AbstractIdentifier getLinksIfNecessary(final AbstractIdentifier id) {

    if (!containsLinks(id)) {
      return id;
    }
    /* Special get!
     * If we get **b, having (*b, c), we give *c
     */
    Optional<AbstractIdentifier> linkedId =
        from(Identifiers.getDereferencedIdentifiers(id))
          .firstMatch(i -> variableBindingRelation.containsKey(i));
    if (linkedId.isPresent()) {
      AbstractIdentifier pointsFrom = linkedId.get();
      int delta = id.getDereference() - pointsFrom.getDereference();
      AbstractIdentifier initialId = variableBindingRelation.get(linkedId);
      AbstractIdentifier pointsTo =
          initialId.cloneWithDereference(initialId.getDereference() + delta);
      if (this.containsLinks(pointsTo)) {
        pointsTo = getLinksIfNecessary(pointsTo);
      }
      return pointsTo;
    }

    return null;
  }

  @Override
  public UsageState clone() {
    return clone(this.getWrappedState());
  }

  public UsageState clone(final AbstractState pWrappedState) {
    return new UsageState(pWrappedState, this);
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + Objects.hashCode(variableBindingRelation);
    result = prime * super.hashCode();
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null ||
        getClass() != obj.getClass()) {
      return false;
    }
    UsageState other = (UsageState) obj;
    return Objects.equals(variableBindingRelation, other.variableBindingRelation)
        && super.equals(other);
  }

  @Override
  public String toString() {
    StringBuilder str = new StringBuilder();

    str.append("[");
    str.append(
      from(variableBindingRelation.keySet())
        .transform(id -> id.toString() + "->" + variableBindingRelation.get(id).toString())
        .join(Joiner.on(", "))
    );
    str.append("]\n");
    str.append(getWrappedState());
    return str.toString();
  }

  boolean isLessOrEqual(final UsageState other) {
    //If we are here, the wrapped domain return true and the stop depends only on this value

    // this element is not less or equal than the other element, if that one contains less elements
    if (this.variableBindingRelation.size() > other.variableBindingRelation.size()) {
      return false;
    }

    // also, this element is not less or equal than the other element,
    // if any one constant's value of the other element differs from the constant's value in this element
    for (AbstractIdentifier id : variableBindingRelation.keySet()) {
      if (!other.variableBindingRelation.containsKey(id)) {
        return false;
      }
    }

    // in case of true, we need to copy usages
    /*for (SingleIdentifier id : this.recentUsages.keySet()) {
      for (UsageInfo usage : this.recentUsages.get(id)) {
        other.addUsage(id, usage);
      }
    }*/
    return true;
  }

  public void addUsage(final SingleIdentifier id, final UsageInfo usage) {
    //Clone it
    if (!isStorageCloned) {
      recentUsages = recentUsages.clone();
      isStorageCloned = true;
    }
    recentUsages.add(id, usage);
  }

  public void joinContainerFrom(final UsageState reducedState) {
    stats.joinTimer.start();
    functionContainer.join(reducedState.functionContainer);
    stats.joinTimer.stop();
  }

  public UsageState reduce(final AbstractState wrappedState) {
    LockState rootLockState = AbstractStates.extractStateByType(this, LockState.class);
    LockState reducedLockState = AbstractStates.extractStateByType(wrappedState, LockState.class);
    List<LockEffect> difference;
    if (rootLockState == null && reducedLockState == null) {
      //No LockCPA
      difference = Collections.emptyList();
    } else {
      difference = reducedLockState.getDifference(rootLockState);
    }

    return new UsageState(wrappedState, new HashMap<>(), recentUsages.clone(),
        this.globalContainer, true, functionContainer.clone(difference), this.stats, this.isExitState);
  }

  public UsageContainer getContainer() {
    return globalContainer;
  }

  public void saveUnsafesInContainerIfNecessary(AbstractState abstractState) {
    ARGState argState = AbstractStates.extractStateByType(abstractState, ARGState.class);
    PredicateAbstractState state = AbstractStates.extractStateByType(argState, PredicateAbstractState.class);
    if (state == null || (!state.getAbstractionFormula().isFalse() && state.isAbstractionState())) {
      recentUsages.setKeyState(argState);
      stats.addRecentUsagesTimer.start();
      functionContainer.join(recentUsages);
      stats.addRecentUsagesTimer.stop();
      recentUsages.clear();
    }
  }

  public void updateContainerIfNecessary() {
    globalContainer.addNewUsagesIfNecessary(functionContainer);
  }

  public void asExitable() {
    //return new UsageExitableState(this);
    isExitState = true;
  }

  public StateStatistics getStatistics() {
    return stats;
  }

  @Override
  public boolean isExitState() {
    return isExitState;
  }

  /*public class UsageExitableState extends UsageState {

    private static final long serialVersionUID = 1957118246209506994L;

    private UsageExitableState(AbstractState pWrappedElement, UsageState state) {
      super(pWrappedElement, state);
    }

    public UsageExitableState(UsageState state) {
      this(state.getWrappedState(), state);
    }

    @Override
    public UsageExitableState clone(final AbstractState wrapped) {
      return new UsageExitableState(wrapped, this);
    }

    @Override
    public UsageExitableState reduce(final AbstractState wrapped) {
      return new UsageExitableState(wrapped, this);
    }

    public boolean isExitable() {
      return true;
    }
  }*/

  public static class StateStatistics {
    private Timer expandTimer = new Timer();
    private Timer joinTimer = new Timer();
    private Timer addRecentUsagesTimer = new Timer();

    private final StorageStatistics storageStats;

    public StateStatistics(StorageStatistics stats) {
      storageStats = Objects.requireNonNull(stats);
    }

    public void printStatistics(PrintStream out) {
      out.println("");
      out.println("Time for lock difference calculation:" + expandTimer);
      out.println("Time for joining:                    " + joinTimer);
      out.println("Time for adding recent usages:       " + addRecentUsagesTimer);
      storageStats.printStatistics(out);
    }
  }

  public static UsageState get(AbstractState state) {
    return AbstractStates.extractStateByType(state, UsageState.class);
  }
}
