/*
 * Copyright 2012 gitblit.com.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.gitblit;

import java.text.MessageFormat;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gitblit.models.RepositoryModel;
import com.gitblit.utils.JGitUtils;
import com.gitblit.utils.LuceneUtils;
import com.gitblit.utils.LuceneUtils.IndexResult;

/**
 * The Lucene executor handles indexing repositories synchronously and
 * asynchronously from a queue.
 * 
 * @author James Moger
 * 
 */
public class LuceneExecutor implements Runnable {

	private final Logger logger = LoggerFactory.getLogger(LuceneExecutor.class);

	private final Queue<String> queue = new ConcurrentLinkedQueue<String>();

	private final IStoredSettings settings;

	private final boolean isLuceneEnabled;

	private final boolean isPollingMode;

	private final AtomicBoolean firstRun = new AtomicBoolean(true);

	public LuceneExecutor(IStoredSettings settings) {
		this.settings = settings;
		this.isLuceneEnabled = settings.getBoolean(Keys.lucene.enable, false);
		this.isPollingMode = settings.getBoolean(Keys.lucene.pollingMode, false);
	}

	/**
	 * Indicates if the Lucene executor can index repositories.
	 * 
	 * @return true if the Lucene executor is ready to index repositories
	 */
	public boolean isReady() {
		return isLuceneEnabled;
	}

	/**
	 * Returns the status of the Lucene queue.
	 * 
	 * @return true, if the queue is empty
	 */
	public boolean hasEmptyQueue() {
		return queue.isEmpty();
	}

	/**
	 * Queues a repository to be asynchronously indexed.
	 * 
	 * @param repository
	 * @return true if the repository was queued
	 */
	public boolean queue(RepositoryModel repository) {
		if (!isReady()) {
			return false;
		}
		queue.add(repository.name);
		return true;
	}

	@Override
	public void run() {
		if (!isLuceneEnabled) {
			return;
		}

		if (firstRun.get() || isPollingMode) {
			// update all indexes on first run or if polling mode
			firstRun.set(false);
			queue.addAll(GitBlit.self().getRepositoryList());
		}

		Set<String> processed = new HashSet<String>();
		if (!queue.isEmpty()) {
			// update the repository Lucene index
			String repositoryName = null;
			while ((repositoryName = queue.poll()) != null) {
				if (processed.contains(repositoryName)) {
					// skipping multi-queued repository
					continue;
				}
				try {
					Repository repository = GitBlit.self().getRepository(repositoryName);
					if (repository == null) {
						logger.warn(MessageFormat.format(
								"Lucene executor could not find repository {0}. Skipping.",
								repositoryName));
						continue;
					}
					index(repositoryName, repository);
					repository.close();
					processed.add(repositoryName);
				} catch (Throwable e) {
					logger.error(MessageFormat.format("Failed to update {0} Lucene index",
							repositoryName), e);
				}
			}
		}
	}

	/**
	 * Synchronously indexes a repository. This may build a complete index of a
	 * repository or it may update an existing index.
	 * 
	 * @param repositoryName
	 *            the name of the repository
	 * @param repository
	 *            the repository object
	 */
	public void index(String repositoryName, Repository repository) {
		try {
			if (JGitUtils.hasCommits(repository)) {
				if (LuceneUtils.shouldReindex(repository)) {
					// (re)build the entire index
					long start = System.currentTimeMillis();
					IndexResult result = LuceneUtils.reindex(repository);
					long duration = System.currentTimeMillis() - start;
					if (result.success) {
						if (result.commitCount > 0) {
							String msg = "Built {0} Lucene index from {1} commits in {2} msecs";
							logger.info(MessageFormat.format(msg, repositoryName,
									result.commitCount, duration));
						}
					} else {
						String msg = "Could not build {0} Lucene index!";
						logger.error(MessageFormat.format(msg, repositoryName));
					}
				} else {
					// update the index with latest commits
					long start = System.currentTimeMillis();
					IndexResult result = LuceneUtils.updateIndex(repository);
					long duration = System.currentTimeMillis() - start;
					if (result.success) {
						if (result.commitCount > 0) {
							String msg = "Updated {0} Lucene index with {1} commits in {2} msecs";
							logger.info(MessageFormat.format(msg, repositoryName,
									result.commitCount, duration));
						}
					} else {
						String msg = "Could not update {0} Lucene index!";
						logger.error(MessageFormat.format(msg, repositoryName));
					}
				}
			} else {
				logger.info(MessageFormat.format("Skipped Lucene index of empty repository {0}",
						repositoryName));
			}
		} catch (Throwable t) {
			logger.error(MessageFormat.format("Lucene indexing failure for {0}", repositoryName), t);
		}
	}

	/**
	 * Close all Lucene indexers.
	 * 
	 */
	public void close() {
		LuceneUtils.close();
	}
}
