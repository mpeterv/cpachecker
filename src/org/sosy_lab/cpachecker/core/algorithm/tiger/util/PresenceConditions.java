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
package org.sosy_lab.cpachecker.core.algorithm.tiger.util;

import com.google.common.base.Optional;

import org.sosy_lab.cpachecker.core.algorithm.tiger.goals.Goal;
import org.sosy_lab.cpachecker.core.interfaces.AbstractState;
import org.sosy_lab.cpachecker.core.interfaces.AbstractStateWithPresenceCondition;
import org.sosy_lab.cpachecker.util.AbstractStates;
import org.sosy_lab.cpachecker.util.globalinfo.GlobalInfo;
import org.sosy_lab.cpachecker.util.presence.interfaces.PresenceCondition;
import org.sosy_lab.cpachecker.util.presence.interfaces.PresenceConditionManager;

import java.util.Set;

import javax.annotation.Nullable;

public class PresenceConditions {

  public static PresenceCondition extractPresenceCondition(AbstractState pAbstractState) {
    AbstractStateWithPresenceCondition e = AbstractStates.extractStateByType(pAbstractState, AbstractStateWithPresenceCondition.class);
    if (e != null) {
      Optional<PresenceCondition> p = e.getPresenceCondition();
      if (p.isPresent()) {
        return p.get();
      }
    }

    return GlobalInfo.getInstance().getPresenceConditionManager().makeTrue();
  }

  public static PresenceCondition composeRemainingPresenceConditions(Set<Goal> pTestGoalsToBeProcessed,
      TestSuite testsuite) {

    PresenceConditionManager pcManager = GlobalInfo.getInstance().getPresenceConditionManager();

    PresenceCondition presenceCondition = pcManager.makeFalse();
    for (Goal goal : pTestGoalsToBeProcessed) {
      presenceCondition = pcManager.makeOr(presenceCondition, testsuite.getRemainingPresenceCondition(goal));
    }

    return presenceCondition;
  }

  public static PresenceCondition orTrue(Optional<PresenceCondition> pPc) {
    if (pPc == null || !pPc.isPresent()) {
      return GlobalInfo.getInstance().getPresenceConditionManager().makeTrue();
    }
    return pPc.get();
  }

  public static PresenceCondition orTrue(@Nullable PresenceCondition pPc) {
    if (pPc == null) {
      return GlobalInfo.getInstance().getPresenceConditionManager().makeTrue();
    }
    return pPc;
  }

  public static PresenceCondition orFalse(Optional<PresenceCondition> pPc) {
    if (pPc == null || !pPc.isPresent()) {
      return GlobalInfo.getInstance().getPresenceConditionManager().makeFalse();
    }
    return pPc.get();
  }

  public static PresenceCondition orFalse(@Nullable PresenceCondition pPc) {
    if (pPc == null) {
      return GlobalInfo.getInstance().getPresenceConditionManager().makeFalse();
    }
    return pPc;
  }

}
