/*
 * Copyright 2007 Sun Microsystems, Inc. All rights reserved
 */

package com.sun.sgs.impl.service.data.store.db.bdbje;

import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.DeadlockException;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.ExceptionEvent;
import com.sleepycat.je.ExceptionListener;
import com.sleepycat.je.LockNotGrantedException;
import com.sleepycat.je.RunRecoveryException;
import com.sleepycat.je.StatsConfig;
import com.sleepycat.je.XAEnvironment;
import com.sun.sgs.app.TransactionAbortedException;
import com.sun.sgs.app.TransactionConflictException;
import com.sun.sgs.app.TransactionTimeoutException;
import com.sun.sgs.impl.service.data.store.Scheduler;
import com.sun.sgs.impl.service.data.store.TaskHandle;
import com.sun.sgs.impl.service.data.store.db.DbDatabase;
import com.sun.sgs.impl.service.data.store.db.DbDatabaseException;
import com.sun.sgs.impl.service.data.store.db.DbEnvironment;
import com.sun.sgs.impl.service.data.store.db.DbTransaction;
import com.sun.sgs.impl.sharedutil.LoggerWrapper;
import com.sun.sgs.impl.sharedutil.PropertiesWrapper;
import com.sun.sgs.service.TransactionParticipant;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.Enumeration;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.transaction.xa.XAException;
import static javax.transaction.xa.XAException.XA_RBBASE;
import static javax.transaction.xa.XAException.XA_RBDEADLOCK;
import static javax.transaction.xa.XAException.XA_RBEND;
import static javax.transaction.xa.XAException.XA_RBTIMEOUT;

/**
 * Provides a database implementation based on <a href=
 * "http://www.oracle.com/database/berkeley-db/je/index.html">Berkeley DB, Java
 * Edition</a>. <p>
 * 
 * Operations on classes in this package will throw an {@link Error} if the
 * underlying Berkeley DB database requires recovery.  In that case, callers
 * need to restart the application or create a new instance of this class. <p>
 *
 * Note that, although databases returned by this class provide support for the
 * {@link DbTransaction#prepare DbTransaction.prepare} method, they do not
 * provide facilities for resolving prepared transactions after a crash.
 * Callers can work around this limitation by insuring that the transaction
 * implementation calls {@link TransactionParticipant#prepareAndCommit
 * TransactionParticipant.prepareAndCommit} to commit transactions on this
 * class.  The current transaction implementation calls
 * <code>prepareAndCommit</code> on durable participants, so the inability to
 * resolve prepared transactions should have no effect at present. <p>
 *
 * The {@link #BdbJeEnvironment constructor} supports the following
 * configuration properties: <p>
 *
 * <dl style="margin-left: 1em">
 *
 * <dt> <i>Property:</i> <code><b>
 *	com.sun.sgs.impl.service.data.store.db.bdbje.flush.to.disk
 *	</b></code> <br>
 *	<i>Default:</i> <code>false</code>
 *
 * <dd style="padding-top: .5em">Whether to flush changes to disk when a
 * transaction commits.  If <code>false</code>, the modifications made in some
 * of the most recent transactions may be lost if the host crashes, although
 * data integrity will be maintained.  Flushing changes to disk avoids data
 * loss but introduces a significant reduction in performance. <p>
 *
 * <dt> <i>Property:</i> <code><b>
 *	com.sun.sgs.impl.service.data.store.db.bdbje.flush.to.disk
 *	</b></code> <br>
 *	<i>Default:</i> <code>-1</code>
 *
 * <dd style="padding-top: .5em">The interval in milliseconds between calls to
 * log database statistics, or a negative value to disable logging.  The
 * property is set to {@code -1} by default, which disables statistics
 * logging. <p>
 *
 * </dl> <p>
 *
 * It also supports any initialization properties supported by the {@link
 * Environment} class that start with the {@code je.} prefix. <p>
 *
 * Unless overridden, this implementation provides the following non-default
 * settings for initialization properties:
 *
 * <dl style="margin-left: 1em">
 *
 * <dt> <i>Property:</i> <code><b>je.checkpointer.bytesInterval</b></code> <br>
 *	<i>Value:</i> <code>1000000</code>
 *
 * <dd style="padding-top: .5em">Perform checkpoints after 1 MB of changes.
 * This setting improves performance when there are a large number of changes
 * being committed. <p>
 *
 * <dt> <i>Property:</i> <code><b>je.env.sharedLatches</b></code> <br>
 *	<i>Value:</i> <code>true</code>
 *
 * <dd style="padding-top: .5em">Use shared latches to improve concurrency. <p>
 *
 * </dl> <p>
 *
 * This class uses the {@link Logger} named
 * <code>com.sun.sgs.impl.service.data.db.bdbje</code> to log information at
 * the following logging levels: <p>
 *
 * <ul>
 * <li> {@link Level#SEVERE SEVERE} - Berkeley DB failures that require
 *	application restart and recovery
 * <li> {@link Level#WARNING WARNING} - Berkeley DB exceptions
 * <li> {@link Level#INFO INFO} - Berkeley DB statistics
 * </ul>
 */
public class BdbJeEnvironment implements DbEnvironment {

    /** The package name. */
    private static final String PACKAGE =
	"com.sun.sgs.impl.service.data.store.db.bdbje";

    /** The logger for this class. */
    static final LoggerWrapper logger =
	new LoggerWrapper(Logger.getLogger(PACKAGE));

    /**
     * The property that specifies whether to flush changes to disk on
     * transaction boundaries.  The property is set to false by default.  If
     * false, some recent transactions may be lost in the event of a crash,
     * although integrity will be maintained.
     */
    private static final String FLUSH_TO_DISK_PROPERTY =
	PACKAGE + ".flush.to.disk";

    /**
     * The property that specifies the interval in milliseconds between calls
     * to log database statistics, or a negative value to disable logging.  The
     * property is set to -1 by default.
     */
    private static final String STATS_PROPERTY = PACKAGE + ".stats";
    
    /**
     * Default values for Berkeley DB Java Edition properties that are
     * different from the BDB defaults.
     */
    private static final Properties defaultProperties = new Properties();
    static {
	defaultProperties.setProperty("je.checkpointer.bytesInterval",
				      "1000000");
	defaultProperties.setProperty("je.env.sharedLatches", "true");
    }

    /** The Berkeley DB environment. */
    private final XAEnvironment env;

    /** The stats task or null. */
    private StatsRunnable statsTask;

    /** Used to cancel the stats task, if non-null. */
    private TaskHandle statsTaskHandle;

    /** A Berkeley DB exception listener that uses logging. */
    private static class LoggingExceptionListener
	implements ExceptionListener
    {
	public void exceptionThrown(ExceptionEvent event) {
	    if (logger.isLoggable(Level.WARNING)) {
		logger.logThrow(Level.WARNING, event.getException(),
				"Database exception in thread {0}",
				event.getThreadName());
	    }
	}
    }

    /** A runnable that logs database statistics. */
    private class StatsRunnable implements Runnable {
	private final StatsConfig statsConfig = new StatsConfig();
	private boolean cancelled = false;
	StatsRunnable() {
	    statsConfig.setClear(true);
	}
	/** Prevents this task from running in the future. */
	synchronized void cancel() {
	    cancelled = true;
	}
	public synchronized void run() {
	    if (!cancelled) {
		try {
		    if (logger.isLoggable(Level.INFO)) {
			logger.log(Level.INFO, "Database stats:\n{0}",
				   env.getStats(statsConfig));
		    }
		} catch (Throwable e) {
		    logger.logThrow(Level.WARNING, e, "Stats failed");
		}
	    }
	}
    }

    /**
     * Creates an instance of this class.
     *
     * @param	directory the directory containing database files
     * @param	properties the properties to configure this instance
     * @param	scheduler the scheduler for running periodic tasks
     * @throws	DbDatabaseException if an unexpected database problem occurs
     */
    public BdbJeEnvironment(
	String directory, Properties properties, Scheduler scheduler)
    {
	if (logger.isLoggable(Level.CONFIG)) {
	    logger.log(Level.CONFIG,
		       "BdbJeEnvironment directory:{0}, properties:{1}, " +
		       "scheduler:{2}",
		       directory, properties, scheduler);
	}
	Properties propertiesWithDefaults = new Properties();
	propertiesWithDefaults.putAll(defaultProperties);
	propertiesWithDefaults.putAll(properties);
	properties = propertiesWithDefaults;
	PropertiesWrapper wrappedProps = new PropertiesWrapper(properties);
	boolean flushToDisk = wrappedProps.getBooleanProperty(
	    FLUSH_TO_DISK_PROPERTY, false);
	long stats = wrappedProps.getLongProperty(STATS_PROPERTY, -1);
	EnvironmentConfig config = new EnvironmentConfig();
	config.setAllowCreate(true);
	config.setExceptionListener(new LoggingExceptionListener());
	config.setTransactional(true);
	config.setTxnWriteNoSync(!flushToDisk);
	for (Enumeration<?> names = properties.propertyNames();
	     names.hasMoreElements(); )
	{
	    Object name = names.nextElement();
	    if (name instanceof String) {
		String property = (String) name;
		if (property.startsWith("je.")) {
		    config.setConfigParam(
			property, properties.getProperty(property));
		}
	    }
	}
	try {
	    env = new XAEnvironment(new File(directory), config);
	} catch (DatabaseException e) {
	    throw convertException(e, false);
	} catch (Error e) {
	    logger.logThrow(
		Level.SEVERE, e, "BdbJeEnvironment initialization failed");
	    throw e;
	}
	if (stats >= 0) {
	    statsTask = new StatsRunnable();
	    statsTaskHandle = scheduler.scheduleRecurringTask(
		statsTask, stats);
	}
    }
	
    /**
     * Returns the correct exception for a Berkeley DB DatabaseException, or
     * XAException, thrown during an operation.  Throws an Error if recovery is
     * needed.  Only converts Berkeley DB transaction exceptions to the
     * associated exceptions if convertTxnExceptions is true.
     */
    static RuntimeException convertException(
	Exception e, boolean convertTxnExceptions)
    {
	if (convertTxnExceptions && e instanceof LockNotGrantedException) {
	    return new TransactionTimeoutException(
		"Transaction timed out: " + e, e);
	} else if (convertTxnExceptions && e instanceof DeadlockException) {
	    return new TransactionConflictException(
		"Transaction conflict: " + e, e);
	} else if (e instanceof RunRecoveryException) {
	    /*
	     * It is tricky to clean up the data structures in this instance in
	     * order to reopen the Berkeley DB databases, because it's hard to
	     * know when they are no longer in use.  It's OK to catch this
	     * Error and create a new DataStoreImpl instance, but this instance
	     * is dead.  -tjb@sun.com (10/19/2006)
	     */
	    Error error = new Error(
		"Database requires recovery -- need to restart the server " +
		"or create a new instance of DataStoreImpl: " + e.getMessage(),
		e);
	    logger.logThrow(Level.SEVERE, error, "Database requires recovery");
	    throw error;
	} else if (e instanceof XAException) {
	    int errorCode = ((XAException) e).errorCode;
	    if (errorCode == XA_RBTIMEOUT) {
		throw new TransactionTimeoutException(
		    "Transaction timed out: " + e, e);
	    } else if (errorCode == XA_RBDEADLOCK) {
		throw new TransactionConflictException(
		    "Transaction conflict: " + e, e);
	    } else if (errorCode >= XA_RBBASE && errorCode <= XA_RBEND) {
		throw new TransactionAbortedException(
		    "Transaction aborted: " + e, e);
	    } else {
		throw new DbDatabaseException(
		    "Unexpected database exception: " + e, e);
	    }
	} else {
	    throw new DbDatabaseException(
		"Unexpected database exception: " + e, e);
	}
    }

    /* -- Implement DbEnvironment -- */

    /** {@inheritDoc} */
    public DbTransaction beginTransaction(long timeout) {
	return new BdbJeTransaction(env, timeout);
    }

    /** {@inheritDoc} */
    public DbDatabase openDatabase(
	DbTransaction txn, String fileName, boolean create)
	throws FileNotFoundException
    {
	return new BdbJeDatabase(
	    env, BdbJeTransaction.getBdbTxn(txn), fileName, create);
    }

    /** {@inheritDoc} */
    public void close() {
	if (statsTaskHandle != null) {
	    statsTask.cancel();
	    statsTaskHandle.cancel();
	    statsTaskHandle = null;
	}
	try {
	    env.close();
	} catch (DatabaseException e) {
	    throw convertException(e, false);
	}
    }
}
