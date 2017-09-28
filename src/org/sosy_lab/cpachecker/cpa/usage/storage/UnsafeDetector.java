/*
 *  CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2015  Dirk Beyer
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

import com.google.common.base.Optional;
import java.util.Set;
import java.util.SortedSet;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.Option;
import org.sosy_lab.common.configuration.Options;
import org.sosy_lab.cpachecker.cpa.usage.UsageInfo;
import org.sosy_lab.cpachecker.cpa.usage.UsageInfo.Access;
import org.sosy_lab.cpachecker.util.Pair;

@Options(prefix="cpa.usage.unsafedetector")
public class UnsafeDetector {
  @Option(name="ignoreEmptyLockset", description="ignore unsafes only with empty callstacks",
      secure = true)
  private boolean ignoreEmptyLockset = false;

  public UnsafeDetector(Configuration config) throws InvalidConfigurationException {
    config.inject(this);
  }

  public boolean isUnsafe(AbstractUsagePointSet set) {
    if (set instanceof RefinedUsagePointSet) {
      return true;
    }
    return isUnsafe((UnrefinedUsagePointSet)set);
  }

  public boolean isUnsafe(Set<UsageInfo> set) {
    return isUnsafe(preparePointSet(set));
  }

  private UnrefinedUsagePointSet preparePointSet(Set<UsageInfo> set) {
    UnrefinedUsagePointSet tmpSet = new UnrefinedUsagePointSet();
    set.forEach(tmpSet::add);
    return tmpSet;
  }

  private boolean isUnsafe(UnrefinedUsagePointSet set) {
    return isUnsafe(set.getTopUsages());
  }

  public Pair<UsageInfo, UsageInfo> getUnsafePair(AbstractUsagePointSet set) {
    assert isUnsafe(set);

    if (set instanceof RefinedUsagePointSet) {
      return ((RefinedUsagePointSet)set).getUnsafePair();
    } else {
      UnrefinedUsagePointSet unrefinedSet = (UnrefinedUsagePointSet) set;
      Pair<UsagePoint, UsagePoint> result = getUnsafePair(unrefinedSet.getTopUsages());

      assert result != null;

      return Pair.of(unrefinedSet.getUsageInfo(result.getFirst()).getOneExample(),
          unrefinedSet.getUsageInfo(result.getSecond()).getOneExample());
    }
  }

  public Pair<UsagePoint, UsagePoint> getUnsafePointPair(UnrefinedUsagePointSet set) {
    return getUnsafePair(set.getTopUsages());
  }

  private boolean isUnsafe(SortedSet<UsagePoint> points) {
    for (UsagePoint point1 : points) {
      if (from(points.tailSet(point1))
          .anyMatch(p -> isUnsafePair(point1, p))) {
        return true;
      }
    }
    return false;
  }

  private Pair<UsagePoint, UsagePoint> getUnsafePair(SortedSet<UsagePoint> set) {

    for (UsagePoint point1 : set) {
      for (UsagePoint point2 : set.tailSet(point1)) {
        if (point1.equals(point2)) {
          /* There can be an unsafe even with only one usage,
           * but at first we find two different usages
           */
          continue;
        }
        if (isUnsafePair(point1, point2)) {
          return Pair.of(point1, point2);
        }
      }
    }
    //Now we find an unsafe only from one usage
    if (!ignoreEmptyLockset) {
      Optional<UsagePoint> result =
          from(set).firstMatch(p -> isUnsafePair(p, p));

      if (result.isPresent()) {
        return Pair.of(result.get(), result.get());
      }
    }
    //If we can not find an unsafe here, fail
    return null;
  }

  public boolean isUnsafePair(UsagePoint point1, UsagePoint point2) {
    if (point1.isCompatible(point2) &&
        (point1.access == Access.WRITE || point2.access == Access.WRITE)) {
      if (ignoreEmptyLockset && point1.isEmpty() && point2.isEmpty()) {
        return false;
      }
      return true;
    }
    return false;
  }
 }
