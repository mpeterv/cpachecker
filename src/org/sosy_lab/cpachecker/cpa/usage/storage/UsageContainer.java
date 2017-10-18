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
package org.sosy_lab.cpachecker.cpa.usage.storage;

import static com.google.common.collect.FluentIterable.from;

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import java.io.PrintStream;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Level;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.Option;
import org.sosy_lab.common.configuration.Options;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.common.time.Timer;
import org.sosy_lab.cpachecker.cpa.usage.UsageInfo;
import org.sosy_lab.cpachecker.cpa.usage.UsageState;
import org.sosy_lab.cpachecker.cpa.usage.refinement.RefinementResult;
import org.sosy_lab.cpachecker.util.Pair;
import org.sosy_lab.cpachecker.util.identifiers.SingleIdentifier;

@Options(prefix="cpa.usage")
public class UsageContainer {
  private final SortedMap<SingleIdentifier, UnrefinedUsagePointSet> unrefinedIds;
  private final SortedMap<SingleIdentifier, RefinedUsagePointSet> refinedIds;
  private final SortedMap<SingleIdentifier, RefinedUsagePointSet> failedIds;

  private final UnsafeDetector detector;

  private final Set<SingleIdentifier> falseUnsafes;

  private final Set<SingleIdentifier> processedUnsafes = new HashSet<>();
  //Only for statistics
  private Set<SingleIdentifier> initialSet = null;
  private int initialUsages;

  private final LogManager logger;

  public Timer resetTimer = new Timer();

  int unsafeUsages = -1;
  int totalIds = 0;

  @Option(description="output only true unsafes",
      secure = true)
  private boolean printOnlyTrueUnsafes = false;

  public UsageContainer(Configuration config, LogManager l) throws InvalidConfigurationException {
    this(new TreeMap<SingleIdentifier, UnrefinedUsagePointSet>(),
        new TreeMap<SingleIdentifier, RefinedUsagePointSet>(),
        new TreeMap<SingleIdentifier, RefinedUsagePointSet>(),
        new TreeSet<SingleIdentifier>(), l, new UnsafeDetector(config));
    config.inject(this);
  }

  private UsageContainer(SortedMap<SingleIdentifier, UnrefinedUsagePointSet> pUnrefinedStat,
      SortedMap<SingleIdentifier, RefinedUsagePointSet> pRefinedStat,
      SortedMap<SingleIdentifier, RefinedUsagePointSet> failedStat,
      Set<SingleIdentifier> pFalseUnsafes, LogManager pLogger,
      UnsafeDetector pDetector) {
    unrefinedIds = pUnrefinedStat;
    refinedIds = pRefinedStat;
    failedIds = failedStat;
    falseUnsafes = pFalseUnsafes;
    logger = pLogger;
    detector = pDetector;
  }

  public void addNewUsagesIfNecessary(FunctionContainer storage) {
    if (unsafeUsages == -1) {
      copyUsages(storage);
      getUnsafesIfNecessary();
    }
  }

  public void forceAddNewUsages(TemporaryUsageStorage storage) {
    //This is a case of 'abort'-functions
    assert (unsafeUsages == -1);
    copyUsages(storage);
  }

  private void copyUsages(AbstractUsageStorage storage) {
    storage.forEach((id, list) ->
      from(list)
        .filter(info -> info.getKeyState() != null)
        .forEach(info -> this.add(id, info))
        );
  }

  public void add(final SingleIdentifier id, final UsageInfo usage) {
    UnrefinedUsagePointSet uset;

    if (falseUnsafes.contains(id)
        || refinedIds.containsKey(id)) {
      return;
    }
    if (!unrefinedIds.containsKey(id)) {
      uset = new UnrefinedUsagePointSet();
      unrefinedIds.put(id, uset);
    } else {
      uset = unrefinedIds.get(id);
    }
    uset.add(usage);
  }

  private void getUnsafesIfNecessary() {
    if (unsafeUsages == -1) {
      processedUnsafes.clear();
      unsafeUsages = 0;
      Set<SingleIdentifier> toDelete = new HashSet<>();

      for (SingleIdentifier id : unrefinedIds.keySet()) {
        UnrefinedUsagePointSet tmpList = unrefinedIds.get(id);
        if (detector.isUnsafe(tmpList)) {
          unsafeUsages += tmpList.size();
        } else {
          toDelete.add(id);
          falseUnsafes.add(id);
        }
      }
      toDelete.forEach(id -> removeIdFromCaches(id));

      refinedIds.forEach((id, list) -> unsafeUsages += list.size());

      if (initialSet == null) {
        assert refinedIds.isEmpty();
        initialSet = Sets.newHashSet(unrefinedIds.keySet());
        initialUsages = unsafeUsages;
      }
    }
  }

  private void removeIdFromCaches(SingleIdentifier id) {
    unrefinedIds.remove(id);
    processedUnsafes.add(id);
  }

  public Set<SingleIdentifier> getAllUnsafes() {
    getUnsafesIfNecessary();
    Set<SingleIdentifier> result = new TreeSet<>(unrefinedIds.keySet());
    result.addAll(refinedIds.keySet());
    result.addAll(failedIds.keySet());
    return result;
  }

  public Set<SingleIdentifier> getInitialUnsafes() {
    return initialSet;
  }

  public Iterator<SingleIdentifier> getUnsafeIterator() {
    if (printOnlyTrueUnsafes) {
      return getTrueUnsafeIterator();
    } else {
      return getAllUnsafes().iterator();
    }
  }

  public Iterator<SingleIdentifier> getUnrefinedUnsafeIterator() {
    //New set to avoid concurrent modification exception
    return getKeySetIterator(unrefinedIds);
  }

  public Iterator<SingleIdentifier> getTrueUnsafeIterator() {
    //New set to avoid concurrent modification exception
    return getKeySetIterator(refinedIds);
  }

  private Iterator<SingleIdentifier> getKeySetIterator(SortedMap<SingleIdentifier, ? extends AbstractUsagePointSet> map) {
    Set<SingleIdentifier> result = new TreeSet<>(map.keySet());
    return result.iterator();
  }

  public int getUnsafeSize() {
    getUnsafesIfNecessary();
    if (printOnlyTrueUnsafes) {
      return refinedIds.size();
    } else {
      return getTotalUnsafeSize();
    }
  }

  public int getTotalUnsafeSize() {
    return unrefinedIds.size() + refinedIds.size() + failedIds.size();
  }

  public int getProcessedUnsafeSize() {
    return refinedIds.size() + failedIds.size();
  }

  public UnsafeDetector getUnsafeDetector() {
    return detector;
  }

  public void resetUnrefinedUnsafes() {
    resetTimer.start();
    unsafeUsages = -1;
    unrefinedIds.forEach((id, s) -> s.reset());
    logger.log(Level.FINE, "Unsafes are reseted");
    resetTimer.stop();
  }

  public void removeState(final UsageState pUstate) {
    unrefinedIds.forEach((id, uset) -> uset.remove(pUstate));
    logger.log(Level.ALL, "All unsafes related to key state " + pUstate + " were removed from reached set");
  }

  public AbstractUsagePointSet getUsages(SingleIdentifier id) {
    if (unrefinedIds.containsKey(id)) {
      return unrefinedIds.get(id);
    } else if (refinedIds.containsKey(id)){
      return refinedIds.get(id);
    } else {
      return failedIds.get(id);
    }
  }

  public void setAsFalseUnsafe(SingleIdentifier id) {
    falseUnsafes.add(id);
    removeIdFromCaches(id);
  }

  public void setAsRefined(SingleIdentifier id, RefinementResult result) {
    Preconditions.checkArgument(result.isTrue(), "Result is not true, can not set the set as refined");
    Preconditions.checkArgument(detector.isUnsafe(getUsages(id)), "Refinement is successful, but the unsafe is absent for identifier " + id);

    setAsRefined(id, result.getTrueRace().getFirst(), result.getTrueRace().getSecond());
  }

  public void setAsRefined(SingleIdentifier id, UsageInfo firstUsage, UsageInfo secondUsage) {
    RefinedUsagePointSet rSet = RefinedUsagePointSet.create(firstUsage, secondUsage);
    if (firstUsage.isLooped() || secondUsage.isLooped()) {
      failedIds.put(id, rSet);
    } else {
      refinedIds.put(id, rSet);
    }
    removeIdFromCaches(id);
  }

  public void printUsagesStatistics(final PrintStream out) {
    int unsafeSize = getTotalUnsafeSize();
    int topUsagePoints = 0, maxTopUsagePoints = 0;

    final int generalUnrefinedSize = unrefinedIds.keySet().size();
    int unrefinedUsages = 0, maxUnrefinedUsages = 0;
    for (UnrefinedUsagePointSet uset : unrefinedIds.values()) {
      unrefinedUsages += uset.size();
      if (maxUnrefinedUsages < uset.size()) {
        maxUnrefinedUsages = uset.size();
      }
      topUsagePoints += uset.getNumberOfTopUsagePoints();
      if (maxTopUsagePoints < uset.getNumberOfTopUsagePoints()) {
        maxTopUsagePoints = uset.getNumberOfTopUsagePoints();
      }
    }

    final int generalRefinedSize = refinedIds.keySet().size();
    int refinedUsages = 0;
    for (RefinedUsagePointSet uset : refinedIds.values()) {
      refinedUsages += uset.size();
    }

    final int generalFailedSize = failedIds.keySet().size();
    int failedUsages = 0;
    for (RefinedUsagePointSet uset : failedIds.values()) {
      Pair<UsageInfo, UsageInfo> pair = uset.getUnsafePair();
      if (pair.getFirst().isLooped()) {
        failedUsages++;
      }
      if (pair.getSecond().isLooped() && !pair.getFirst().equals(pair.getSecond())) {
        failedUsages++;
      }
    }

    out.println("Total amount of unsafes:                          " + unsafeSize);

    out.println("Initial amount of unsafes (before refinement):    " + initialSet.size());
    out.println("Initial amount of usages (before refinement):     " + initialUsages);
    out.println("Initial amount of refined false unsafes:          " + falseUnsafes.size());

    out.println("Total amount of unrefined unsafes:                " + generalUnrefinedSize);
    out.println("Total amount of unrefined usage points:           " + topUsagePoints + "(avg. " +
        (generalUnrefinedSize == 0 ? "0" : (topUsagePoints/generalUnrefinedSize))
        + ", max. " + maxTopUsagePoints + ")");
    out.println("Total amount of unrefined usages:                 " + unrefinedUsages + "(avg. " +
        (generalUnrefinedSize == 0 ? "0" : (unrefinedUsages/generalUnrefinedSize)) + ", max " + maxUnrefinedUsages + ")");

    out.println("Total amount of refined unsafes:                  " + generalRefinedSize);
    out.println("Total amount of refined usages:                   " + refinedUsages + "(avg. " +
        (generalRefinedSize == 0 ? "0" : (refinedUsages/generalRefinedSize)) + ")");

    out.println("Total amount of failed unsafes:                   " + generalFailedSize);
    out.println("Total amount of failed usages:                    " + failedUsages + "(avg. " +
        (generalFailedSize == 0 ? "0" : (failedUsages/generalFailedSize)) + ")");
  }

  @Override
  public UsageContainer clone() {
    UsageContainer result = new UsageContainer(Maps.newTreeMap(unrefinedIds),
        Maps.newTreeMap(refinedIds), Maps.newTreeMap(failedIds), Sets.newHashSet(falseUnsafes), logger, detector);
    return result;
  }

  public Set<SingleIdentifier> getProcessedUnsafes() {
    return processedUnsafes;
  }
}
