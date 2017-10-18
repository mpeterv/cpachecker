/*
 *  CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2017  Dirk Beyer
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

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.FileOption;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.Option;
import org.sosy_lab.common.configuration.Options;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.common.time.Timer;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.core.reachedset.UnmodifiableReachedSet;
import org.sosy_lab.cpachecker.cpa.arg.ARGPath;
import org.sosy_lab.cpachecker.cpa.arg.ARGState;
import org.sosy_lab.cpachecker.cpa.arg.ARGUtils;
import org.sosy_lab.cpachecker.cpa.bam.BAMMultipleCEXSubgraphComputer;
import org.sosy_lab.cpachecker.cpa.bam.BAMSubgraphComputer.BackwardARGState;
import org.sosy_lab.cpachecker.cpa.bam.BAMSubgraphComputer.MissingBlockException;
import org.sosy_lab.cpachecker.cpa.bam.BAMTransferRelation;
import org.sosy_lab.cpachecker.cpa.lock.LockTransferRelation;
import org.sosy_lab.cpachecker.cpa.usage.storage.AbstractUsagePointSet;
import org.sosy_lab.cpachecker.cpa.usage.storage.UnsafeDetector;
import org.sosy_lab.cpachecker.cpa.usage.storage.UsageContainer;
import org.sosy_lab.cpachecker.util.AbstractStates;
import org.sosy_lab.cpachecker.util.Pair;
import org.sosy_lab.cpachecker.util.identifiers.SingleIdentifier;

@Options(prefix="cpa.usage")
public abstract class ErrorTracePrinter {

  @Option(name="falseUnsafesOutput", description="path to write results",
      secure = true)
  @FileOption(FileOption.Type.OUTPUT_FILE)
  private Path outputFalseUnsafes = Paths.get("FalseUnsafes");

  @Option(name="filterMissedFiles", description="if a file do not exist, do not include the corresponding edge",
      secure = true)
  private boolean filterMissedFiles = true;

  @Option(description="print all unsafe cases in report",
      secure = true)
  private boolean printFalseUnsafes = false;

  private final BAMTransferRelation transfer;
  protected final LockTransferRelation lockTransfer;
  private UnsafeDetector detector;

  private final Timer preparationTimer = new Timer();
  private final Timer unsafeDetectionTimer = new Timer();
  private final Timer writingUnsafeTimer = new Timer();

  protected final Configuration config;
  protected UsageContainer container;
  protected final LogManager logger;

  protected Predicate<CFAEdge> FILTER_EMPTY_FILE_LOCATIONS;


  public ErrorTracePrinter(Configuration c, BAMTransferRelation t, LogManager l, LockTransferRelation lT) throws InvalidConfigurationException {
    transfer = t;
    logger = l;
    config = c;
    lockTransfer = lT;
    config.inject(this, ErrorTracePrinter.class);
    FILTER_EMPTY_FILE_LOCATIONS =
        e -> (e.getFileLocation() != null && !e.getFileLocation().getFileName().equals("<none>"));

    if (filterMissedFiles) {
      FILTER_EMPTY_FILE_LOCATIONS = Predicates.and(
          FILTER_EMPTY_FILE_LOCATIONS,
          e -> (Files.exists(Paths.get(e.getFileLocation().getFileName())))
          );
    }
  }

  private void createPath(UsageInfo usage) {
    assert usage.getKeyState() != null;

    Function<ARGState, Integer> dummyFunc = s -> s.getStateId();
    BAMMultipleCEXSubgraphComputer subgraphComputer = transfer.createBAMMultipleSubgraphComputer(dummyFunc);
    ARGState target = (ARGState)usage.getKeyState();
    BackwardARGState newTreeTarget = new BackwardARGState(target);
    try {
      ARGState root = subgraphComputer.findPath(newTreeTarget, Collections.emptySet());
      ARGPath path = ARGUtils.getRandomPath(root);

      //path is transformed internally
      usage.setRefinedPath(path.getInnerEdges());
    } catch (MissingBlockException | InterruptedException e) {
      logger.log(Level.SEVERE, "Exception during creating path: " + e.getMessage());
    }
  }

  protected String createUniqueName(SingleIdentifier id) {
    return id.getType()
        .toASTString(id.getName())
        .replace(" ", "_");
  }

  public void printErrorTraces(UnmodifiableReachedSet reached) {
    preparationTimer.start();
    ARGState firstState = AbstractStates.extractStateByType(reached.getFirstState(), ARGState.class);
    //getLastState() returns not the correct last state
    Collection<ARGState> children = firstState.getChildren();
    if (!children.isEmpty()) {
      //Analysis finished normally
      ARGState lastState = firstState.getChildren().iterator().next();
      UsageState USlastState = UsageState.get(lastState);
      USlastState.updateContainerIfNecessary();
      container = USlastState.getContainer();
    } else {
      container = UsageState.get(firstState).getContainer();
    }
    detector = container.getUnsafeDetector();

    logger.log(Level.FINEST, "Processing unsafe identifiers");
    Iterator<SingleIdentifier> unsafeIterator;
    unsafeIterator = container.getUnsafeIterator();

    init();
    preparationTimer.stop();
    while (unsafeIterator.hasNext()) {
      SingleIdentifier id = unsafeIterator.next();
      final AbstractUsagePointSet uinfo = container.getUsages(id);

      if (uinfo == null || uinfo.size() == 0) {
        continue;
      }

      unsafeDetectionTimer.start();
      if (!detector.isUnsafe(uinfo)) {
        //In case of interruption during refinement,
        //We may get a situation, when a path is removed, but the verdict is not updated
        unsafeDetectionTimer.stop();
        continue;
      }
      Pair<UsageInfo, UsageInfo> tmpPair = detector.getUnsafePair(uinfo);
      unsafeDetectionTimer.stop();
      writingUnsafeTimer.start();
      printUnsafe(id, tmpPair);
      writingUnsafeTimer.stop();
    }
    if (printFalseUnsafes) {
      Set<SingleIdentifier> currentUnsafes = container.getAllUnsafes();
      Set<SingleIdentifier> initialUnsafes = container.getInitialUnsafes();
      Set<SingleIdentifier> falseUnsafes = Sets.difference(initialUnsafes, currentUnsafes);

      if (!falseUnsafes.isEmpty()) {
        try (Writer writer = Files.newBufferedWriter(Paths.get(outputFalseUnsafes.toString()), Charset.defaultCharset())) {
          logger.log(Level.FINE, "Print statistics about false unsafes");

          for (SingleIdentifier id : falseUnsafes) {
            writer.append(createUniqueName(id) + "\n");
          }
        } catch (IOException e) {
          logger.log(Level.SEVERE, e.getMessage());
        }
      }
    }
    finish();
  }

  public void printStatistics(final PrintStream out) {

    container.printUsagesStatistics(out);

    out.println("");
    out.println("Time for preparation:          " + preparationTimer);
    out.println("Time for unsafe detection:     " + unsafeDetectionTimer);
    out.println("Time for dumping the unsafes:  " + writingUnsafeTimer);
    out.println("Time for reseting unsafes:     " + container.resetTimer);
  }

  protected String getNoteFor(CFAEdge pEdge) {
    if (lockTransfer != null) {
      return lockTransfer.doesChangeTheState(pEdge);
    } else {
      return null;
    }
  }

  protected List<CFAEdge> getPath(UsageInfo usage) {
    if (usage.getPath() == null) {
      createPath(usage);
    }
    List<CFAEdge> path = usage.getPath();

    if (path.isEmpty()) {
      return null;
    }
    return path;
  }

  protected abstract void printUnsafe(SingleIdentifier id, Pair<UsageInfo, UsageInfo> pair);
  protected void init() {}
  protected void finish() {}
}
