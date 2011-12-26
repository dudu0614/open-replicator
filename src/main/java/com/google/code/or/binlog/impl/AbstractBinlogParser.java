/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.code.or.binlog.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.code.or.binlog.BinlogEventFilter;
import com.google.code.or.binlog.BinlogEventListener;
import com.google.code.or.binlog.BinlogEventParser;
import com.google.code.or.binlog.BinlogEventV4;
import com.google.code.or.binlog.BinlogParser;
import com.google.code.or.binlog.BinlogParserContext;
import com.google.code.or.binlog.impl.event.TableMapEvent;
import com.google.code.or.binlog.impl.parser.NopEventParser;
import com.google.code.or.common.util.XThreadFactory;
import com.google.code.or.io.XInputStream;

/**
 * 
 * @author Jingqi Xu
 */
public abstract class AbstractBinlogParser implements BinlogParser {
	//
	private static final Logger LOGGER = LoggerFactory.getLogger(AbstractBinlogParser.class);
	
	//
	protected Thread worker;
	protected ThreadFactory threadFactory;
	protected BinlogEventFilter eventFilter;
	protected BinlogEventListener eventListener;
	protected final Context context = new Context();
	protected final AtomicBoolean verbose = new AtomicBoolean(false);
	protected final AtomicBoolean running = new AtomicBoolean(false);
	protected final BinlogEventParser defaultParser = new NopEventParser();
	protected final BinlogEventParser[] parsers = new BinlogEventParser[128];
	
	//
	protected abstract void parse(XInputStream is) throws Exception;

	
	/**
	 * 
	 */
	public AbstractBinlogParser() {
		this.threadFactory = new XThreadFactory("binlog-parser", false);
	}
	
	/**
	 * 
	 */
	public boolean isRunning() {
		return this.running.get();
	}
	
	public void start(XInputStream is) throws Exception {
		//
		if(!this.running.compareAndSet(false, true)) {
			return;
		}
		
		//
		this.worker = this.threadFactory.newThread(new ParserTask(is));
		this.worker.start();
	}

	public void stop(long timeout, TimeUnit unit) throws Exception {
		//
		if(!this.running.compareAndSet(true, false)) {
			return;
		}
		
		//
		if(timeout > 0) {
			unit.timedJoin(this.worker, timeout);
			this.worker = null;
		}
	}
	
	/**
	 * 
	 */
	public boolean isVerbose() {
		return this.verbose.get();
	}
	
	public void setVerbose(boolean verbose) {
		this.verbose.set(verbose);
	}
	
	public ThreadFactory getThreadFactory() {
		return threadFactory;
	}

	public void setThreadFactory(ThreadFactory tf) {
		this.threadFactory = tf;
	}
	
	public BinlogEventFilter getEventFilter() {
		return eventFilter;
	}

	public void setEventFilter(BinlogEventFilter filter) {
		this.eventFilter = filter;
	}
	
	public BinlogEventListener getEventListener() {
		return eventListener;
	}
	
	public void setEventListener(BinlogEventListener listener) {
		this.eventListener = listener;
	}
	
	/**
	 * 
	 */
	public BinlogEventParser getEventParser(int type) {
		return this.parsers[type];
	}
	
	public BinlogEventParser unregistgerEventParser(int type) {
		return this.parsers[type] = null;
	}
	
	public void registgerEventParser(BinlogEventParser parser) {
		this.parsers[parser.getEventType()] = parser;
	}
	
	public void setEventParsers(List<BinlogEventParser> parsers) {
		//
		for(int i = 0; i < this.parsers.length; i++) {
			this.parsers[i] = null;
		}
		
		// 
		if(parsers != null)  {
			for(BinlogEventParser parser : parsers) {
				registgerEventParser(parser);
			}
		}
	}
	
	/**
	 * 
	 */
	protected final class ParserTask implements Runnable {
		//
		private final XInputStream is;
		
		/**
		 * 
		 */
		public ParserTask(XInputStream is) {
			this.is = is;
		}

		/**
		 * 
		 */
		public void run() {
			try {
				parse(this.is);
			} catch (Exception e) {
				LOGGER.error("failed to parse binlog", e);
			} finally {
				try {
					stop(0, TimeUnit.MILLISECONDS);
				} catch(Exception e) {
					LOGGER.error("failed to stop parser", e);
				}
			}
		}
	}
	
	protected class Context implements BinlogParserContext, BinlogEventListener {
		//
		private Map<Long, TableMapEvent> tableMaps = new HashMap<Long, TableMapEvent>();
		
		/**
		 * 
		 */
		public BinlogEventListener getListener() {
			return this;
		}

		public TableMapEvent getTableMapEvent(long tableId) {
			return this.tableMaps.get(tableId);
		}
		
		/**
		 * 
		 */
		public void onEvents(BinlogEventV4 event) {
			//
			if(event == null) {
				return;
			}
			
			//
			if(event instanceof TableMapEvent) {
				final TableMapEvent tme = (TableMapEvent)event;
				this.tableMaps.put(tme.getTableId(), tme);
			}
			
			//
			AbstractBinlogParser.this.eventListener.onEvents(event);
		}
	}
}
