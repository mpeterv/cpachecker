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
package org.sosy_lab.cpachecker.cpa.usage.refinement;

import static com.google.common.collect.FluentIterable.from;

import com.google.common.base.Predicates;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.logging.Level;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.Option;
import org.sosy_lab.common.configuration.Options;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.core.interfaces.ConfigurableProgramAnalysis;
import org.sosy_lab.cpachecker.core.interfaces.Precision;
import org.sosy_lab.cpachecker.core.interfaces.StateSpacePartition;
import org.sosy_lab.cpachecker.core.reachedset.ReachedSet;
import org.sosy_lab.cpachecker.cpa.arg.ARGState;
import org.sosy_lab.cpachecker.cpa.bam.BAMTransferRelation;
import org.sosy_lab.cpachecker.cpa.bam.MultipleARGSubtreeRemover;
import org.sosy_lab.cpachecker.cpa.predicate.BAMPredicateCPA;
import org.sosy_lab.cpachecker.cpa.predicate.PredicatePrecision;
import org.sosy_lab.cpachecker.cpa.usage.UsageCPA;
import org.sosy_lab.cpachecker.cpa.usage.UsageState;
import org.sosy_lab.cpachecker.cpa.usage.storage.AbstractUsagePointSet;
import org.sosy_lab.cpachecker.cpa.usage.storage.UnrefinedUsagePointSet;
import org.sosy_lab.cpachecker.cpa.usage.storage.UsageContainer;
import org.sosy_lab.cpachecker.exceptions.CPAException;
import org.sosy_lab.cpachecker.util.AbstractStates;
import org.sosy_lab.cpachecker.util.CPAs;
import org.sosy_lab.cpachecker.util.Precisions;
import org.sosy_lab.cpachecker.util.identifiers.SingleIdentifier;


@Options(prefix="cpa.usage")
public class IdentifierIterator extends WrappedConfigurableRefinementBlock<ReachedSet, SingleIdentifier> {

  private final ConfigurableProgramAnalysis cpa;
  private final LogManager logger;

  @Option(name="precisionReset", description="The value of marked unsafes, after which the precision should be cleaned",
      secure = true)
  private int precisionReset = Integer.MAX_VALUE;

  //TODO Option is broken!!
  @Option(name="totalARGCleaning", description="clean all ARG or try to reuse some parts of it (memory consuming)",
      secure = true)
  private boolean totalARGCleaning = false;

  private final BAMTransferRelation transfer;

  int i = 0;
  int lastFalseUnsafeSize = -1;
  int lastTrueUnsafes = -1;

  private final Map<SingleIdentifier, PredicatePrecision> precisionMap = new HashMap<>();

  public IdentifierIterator(ConfigurableRefinementBlock<SingleIdentifier> pWrapper, Configuration config,
      ConfigurableProgramAnalysis pCpa, BAMTransferRelation pTransfer) throws InvalidConfigurationException {
    super(pWrapper);
    config.inject(this);
    cpa = pCpa;
    logger = CPAs.retrieveCPA(pCpa, UsageCPA.class).getLogger();
    transfer = pTransfer;
  }

  @Override
  public RefinementResult performRefinement(ReachedSet pReached) throws CPAException, InterruptedException {
    ARGState firstState = AbstractStates.extractStateByType(pReached.getFirstState(), ARGState.class);
    ARGState lastState = firstState.getChildren().iterator().next();
    UsageState USlastState = UsageState.get(lastState);
    USlastState.updateContainerIfNecessary();
    UsageContainer container = USlastState.getContainer();
    BAMPredicateCPA bamcpa = CPAs.retrieveCPA(cpa, BAMPredicateCPA.class);
    assert bamcpa != null;

    logger.log(Level.INFO, ("Perform US refinement: " + i++));
    int originUnsafeSize = container.getUnsafeSize();
    if (lastFalseUnsafeSize == -1) {
      lastFalseUnsafeSize = originUnsafeSize;
    }
    int counter = lastFalseUnsafeSize - originUnsafeSize;
    boolean newPrecisionFound = false;

    sendUpdateSignal(PredicateRefinerAdapter.class, pReached);
    sendUpdateSignal(UsagePairIterator.class, container);
    sendUpdateSignal(PointIterator.class, container);

    Iterator<SingleIdentifier> iterator = container.getUnrefinedUnsafeIterator();
    boolean isPrecisionChanged = false;
    while (iterator.hasNext()) {
      SingleIdentifier currentId = iterator.next();

      AbstractUsagePointSet pointSet = container.getUsages(currentId);
      if (pointSet instanceof UnrefinedUsagePointSet) {
        RefinementResult result = wrappedRefiner.performRefinement(currentId);
        newPrecisionFound |= result.isFalse();

        PredicatePrecision info = result.getPrecision();

        if (info != null && !info.getLocalPredicates().isEmpty()) {
          PredicatePrecision updatedPrecision;
          if (precisionMap.containsKey(currentId)) {
            updatedPrecision = precisionMap.get(currentId).mergeWith(info);
          } else {
            updatedPrecision = info;
          }
          precisionMap.put(currentId, updatedPrecision);
          isPrecisionChanged = true;
        }

        if (result.isTrue()) {
          container.setAsRefined(currentId, result);
        } else if (result.isFalse() && !isPrecisionChanged) {
          //We do not add a precision, but consider the unsafe as false
          //set it as false now, because it will occur again, as precision is not changed
          //We can not look at precision size here - the result can be false due to heuristics
          container.setAsFalseUnsafe(currentId);
        }
      }
    }
    int newTrueUnsafeSize = container.getProcessedUnsafeSize();
    if (lastTrueUnsafes == -1) {
      //It's normal, if in the first iteration the true unsafes are not involved in counter
      lastTrueUnsafes = newTrueUnsafeSize;
    }
    counter += (newTrueUnsafeSize - lastTrueUnsafes);
    if (counter >= precisionReset) {
      Precision p = pReached.getPrecision(pReached.getFirstState());
      pReached.updatePrecision(pReached.getFirstState(),
          Precisions.replaceByType(p, PredicatePrecision.empty(), Predicates.instanceOf(PredicatePrecision.class)));

      //TODO will we need other finish signal?
      //wrappedRefiner.finish(getClass());
      lastFalseUnsafeSize = originUnsafeSize;
      lastTrueUnsafes = newTrueUnsafeSize;
    }
    if (newPrecisionFound) {
      bamcpa.clearAllCaches();
      ARGState.clearIdGenerator();
      Precision precision = pReached.getPrecision(firstState);
      if (totalARGCleaning) {
        transfer.cleanCaches();
      } else {
        MultipleARGSubtreeRemover subtreesRemover = transfer.getMultipleARGSubtreeRemover();
        subtreesRemover.cleanCaches();
      }
      pReached.clear();
      PredicatePrecision predicates = Precisions.extractPrecisionByType(precision, PredicatePrecision.class);

      from(container.getProcessedUnsafes())
        .transform(precisionMap::remove)
        .filter(Predicates.notNull())
        .forEach(predicates::subtract);

      CFANode firstNode = AbstractStates.extractLocation(firstState);
      //Get new state to remove all links to the old ARG
      pReached.add(cpa.getInitialState(firstNode, StateSpacePartition.getDefaultPartition()), precision);

      //TODO should we signal about removed ids?

      sendFinishSignal();
    }
    //pStat.UnsafeCheck.stopIfRunning();
    if (newPrecisionFound) {
      return RefinementResult.createTrue();
    } else {
      return RefinementResult.createFalse();
    }
  }

  @Override
  public void printStatistics(PrintStream pOut) {
    wrappedRefiner.printStatistics(pOut);
  }
}
