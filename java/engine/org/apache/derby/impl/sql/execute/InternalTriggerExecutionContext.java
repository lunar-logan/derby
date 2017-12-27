/*

   Derby - Class org.apache.derby.impl.sql.execute.InternalTriggerExecutionContext

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */

package org.apache.derby.impl.sql.execute;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Hashtable;
import java.util.Map;
import java.util.Vector;

import org.apache.derby.catalog.UUID;
import org.apache.derby.iapi.db.TriggerExecutionContext;
import org.apache.derby.shared.common.error.ExceptionSeverity;
import org.apache.derby.iapi.error.PublicAPI;
import org.apache.derby.iapi.error.StandardException;
import org.apache.derby.iapi.jdbc.ConnectionContext;
import org.apache.derby.shared.common.reference.SQLState;
import org.apache.derby.iapi.services.i18n.MessageService;
import org.apache.derby.iapi.sql.conn.LanguageConnectionContext;
import org.apache.derby.iapi.sql.dictionary.TriggerDescriptor;
import org.apache.derby.iapi.sql.execute.ConstantAction;
import org.apache.derby.iapi.sql.execute.CursorResultSet;
import org.apache.derby.iapi.sql.execute.ExecRow;
import org.apache.derby.iapi.sql.execute.ExecutionStmtValidator;
import org.apache.derby.iapi.types.DataValueDescriptor;
/**
 * There is one of these beasts per INSERT/DELETE/UPDATE 
 * statement.  It fulfills the contract for the externally
 * visible trigger execution context and it validates
 * that a statement that is about to be executed doesn't
 * violate the restrictions placed upon what can be executed
 * from a trigger.
 * <p>
 * Note that it is crucial that cleanup() is called once
 * the DML has completed, cleanup() makes sure that users
 * can't do something invalid on a tec reference that they
 * were holding from when the trigger fired.
 *
 */
class InternalTriggerExecutionContext
        implements TriggerExecutionContext, ExecutionStmtValidator
{
	/*
	** Immutable
	*/
	protected int					dmlType;
	protected String				statementText;
	protected ConnectionContext		cc;
	protected UUID					targetTableId;
	protected String				targetTableName;
	protected LanguageConnectionContext lcc;

	/*
	** Mutable
	*/
	protected CursorResultSet		beforeResultSet;
	protected CursorResultSet		afterResultSet;

	/**
	 * used exclusively for InsertResultSets which have autoincrement 
	 * columns.
	 */
	protected ExecRow				afterRow;
								
	protected boolean				cleanupCalled;
	protected TriggerEvent			event;
	protected TriggerDescriptor		triggerd;

	/*
	** Used to track all the result sets we have given out to
	** users.  When the trigger context is no longer valid,
	** we close all the result sets that may be in the user
	** space because they can no longer provide meaningful
	** results.
	*/
    @SuppressWarnings("UseOfObsoleteCollectionType")
    private Vector<ResultSet>   resultSetVector;

	/**
	 * aiCounters is a vector of AutoincrementCounters used to keep state which
	 * might be used by the trigger. This is only used by Insert triggers--
	 * Delete and Update triggers do not use this variable.
	 * 
	 * @see AutoincrementCounter
	 * 
	 */
    @SuppressWarnings("UseOfObsoleteCollectionType")
    private Vector<AutoincrementCounter> aiCounters;
	
	/**
     * aiHT is a hash table of auto increment (key, value) pairs. This is used
     * for auto increment values generated by the trigger.
	 */
    @SuppressWarnings("UseOfObsoleteCollectionType")
	private Hashtable<String,Long> aiHT;

	/**
     * Build a trigger execution context.
	 * <p>
	 * About the only thing of real interest to outside observers
	 * is that it pushes itself as the trigger execution context
	 * in the lcc.  Be sure to call <i>cleanup()</i> when you
	 * are done, or you will be flogged like the reprobate that
	 * you are.
	 *
	 * @param lcc	the lcc
	 * @param statementText	the text of the statement that caused the
	 *		trigger to fire.  may be null if we are replicating
	 * @param targetTableId	the UUID of the table upon which the trigger
	 *		fired
	 * @param targetTableName	the name of the table upon which the trigger
	 *		fired
	 * @param aiCounters		A vector of AutoincrementCounters to keep state
	 * 							of the ai columns in this insert trigger.a
	 *
	 * @exception StandardException on error
	 */
    @SuppressWarnings({"UseOfObsoleteCollectionType", "LeakingThisInConstructor"})
    InternalTriggerExecutionContext
	(
		LanguageConnectionContext	lcc,
		ConnectionContext			cc,
		String 						statementText,
		int 						dmlType,
		UUID						targetTableId,
		String						targetTableName,
        Vector<AutoincrementCounter> aiCounters
	) throws StandardException
	{
		this.dmlType = dmlType;
		this.statementText = statementText;
		this.cc = cc;
		this.lcc = lcc;
		this.targetTableId = targetTableId;
		this.targetTableName = targetTableName;
		this.resultSetVector = new Vector<java.sql.ResultSet>();
		this.aiCounters = aiCounters;

		lcc.pushTriggerExecutionContext(this);
	}

	void setBeforeResultSet(CursorResultSet rs)
	{
		beforeResultSet = rs;	
	}

	void setAfterResultSet(CursorResultSet rs)
	     throws StandardException
	{
		afterResultSet = rs;	
		
		if (aiCounters != null)
		{
			if (triggerd.isRowTrigger())
			{
				// An after row trigger needs to see the "first" row inserted 
				rs.open();
				afterRow = rs.getNextRow();
				rs.close();
			}
			else 
			{
				// after statement trigger needs to look at the last value.
				if (!triggerd.isBeforeTrigger())
					resetAICounters(false);
			}
		}
	}

	void setCurrentTriggerEvent(TriggerEvent event)
	{
		this.event = event;
	}

	void clearCurrentTriggerEvent()
	{
		event = null;
	}

	void setTrigger(TriggerDescriptor triggerd)
	{
		this.triggerd = triggerd;
	}
	
	void clearTrigger() throws StandardException
	{
		event = null;
		triggerd = null;
		if (afterResultSet != null)
		{
			afterResultSet.close();
			afterResultSet = null;
		}
		if (beforeResultSet != null)
		{
			beforeResultSet.close();
			beforeResultSet = null;
		}
	}

	/**
	 * Cleanup the trigger execution context.  <B>MUST</B>
	 * be called when the caller is done with the trigger
	 * execution context.
	 * <p>
	 * We go to somewhat exaggerated lengths to free up
	 * all our resources here because a user may hold on
	 * to a TEC after it is valid, so we clean everything
	 * up to be on the safe side.
	 *
	 * @exception StandardException on unexpected error
	 */
	protected void cleanup()
		throws StandardException
	{
        if (lcc != null) {
            lcc.popTriggerExecutionContext(this);
        }

		/*
		** Explicitly close all result sets that we have
		** given out to the user.  
	 	*/
        if (resultSetVector != null) {
            for (ResultSet rs : resultSetVector) {
                try {
                    rs.close();
                } catch (SQLException se) {
                }
            }
		}
		resultSetVector = null;
	
		/*
		** We should have already closed our underlying
		** ExecResultSets by closing the jdbc result sets,
		** but in case we got an error that we caught and
		** ignored, explicitly close them.
		*/	
		if (afterResultSet != null)
		{
			afterResultSet.close();
			afterResultSet = null;
		}
		if (beforeResultSet != null)
		{
			beforeResultSet.close();
			beforeResultSet = null;
		}

		lcc = null;
		cleanupCalled = true;
	}

	/**
	 * Make sure that the user isn't trying to get a result
	 * set after we have cleaned up. 
	 */
	private void ensureProperContext() throws SQLException
	{
		if (cleanupCalled)
		{
			throw new SQLException(
				MessageService.getTextMessage(
									SQLState.LANG_STATEMENT_CLOSED_NO_REASON),
									"XCL31",
									ExceptionSeverity.STATEMENT_SEVERITY
									);
		}
	}

	/////////////////////////////////////////////////////////
	//
	// ExecutionStmtValidator
	//
	/////////////////////////////////////////////////////////
	/**
	 * Make sure that whatever statement is about to be executed
	 * is ok from the context of this trigger.
	 * <p>
	 * Note that we are sub classed in replication for checks
	 * for replication specific language.
	 *
	 * @param constantAction the constant action of the action
	 *	that we are to validate
	 *
	 * @exception StandardException on error
	 */
	public void validateStatement(ConstantAction constantAction) throws StandardException
	{

		// DDL statements are not allowed in triggers. Direct use of DDL
		// statements in a trigger's action statement is disallowed by the
		// parser. However, this runtime check is needed to prevent execution
		// of DDL statements by procedures within a trigger context. 
 		if (constantAction instanceof DDLConstantAction) {
			throw StandardException.newException(SQLState.LANG_NO_DDL_IN_TRIGGER, triggerd.getName());
		}
		
		// No INSERT/UPDATE/DELETE for a before trigger. There is no need to 
 		// check this here because parser does not allow these DML statements
 		// in a trigger's action statement in a before trigger. Parser also 
 		// disallows creation of before triggers calling procedures that modify
 		// SQL data.   
		
	}

	/////////////////////////////////////////////////////////
	//
	// TriggerExectionContext
	//
	/////////////////////////////////////////////////////////

	/**
	 * Get the target table name upon which the 
	 * trigger event is declared.
	 *
	 * @return the target table
	 */
	public String getTargetTableName()
	{
		return targetTableName;
	}

	/**
	 * Get the target table UUID upon which the 
	 * trigger event is declared.
	 *
	 * @return the uuid of the target table
	 */
	public UUID getTargetTableId()
	{
		return targetTableId;
	}

	/**
	 * Get the type for the event that caused the
	 * trigger to fire.
	 *
	 * @return the event type (e.g. UPDATE_EVENT)
	 */
	public int getEventType()
	{
		return dmlType;
	}

	/**
	 * Get the text of the statement that caused the
	 * trigger to fire.
	 *
	 * @return the statement text
	 */
	public String getEventStatementText()
	{
		return statementText;
	}

	/**
	 * Returns a result set row the old images of the changed rows.
	 * For a row trigger, the result set will have a single row.  For
	 * a statement trigger, this result set has every row that has
	 * changed or will change.  If a statement trigger does not affect 
	 * a row, then the result set will be empty (i.e. ResultSet.next()
	 * will return false).
	 *
	 * @return the ResultSet containing before images of the rows 
	 * changed by the triggering event.
	 *
	 * @exception SQLException if called after the triggering event has
	 * completed
	 */
	public java.sql.ResultSet getOldRowSet() throws SQLException
	{
		ensureProperContext();
		if (beforeResultSet == null)
		{
			return null;
		}

		try
		{
			CursorResultSet brs = beforeResultSet;
			/* We should really shallow clone the result set, because it could be used
			 * at multiple places independently in trigger action.  This is a bug found
			 * during the fix of beetle 4373.
			 */
			if (brs instanceof TemporaryRowHolderResultSet)
				brs = (CursorResultSet) ((TemporaryRowHolderResultSet) brs).clone();
			else if (brs instanceof TableScanResultSet)
				brs = (CursorResultSet) ((TableScanResultSet) brs).clone();
			brs.open();
			java.sql.ResultSet rs = cc.getResultSet(brs);
			resultSetVector.addElement(rs);
			return rs;
		} catch (StandardException se)
		{
			throw PublicAPI.wrapStandardException(se);
		}
	}

	/**
	 * Returns a result set row the new images of the changed rows.
	 * For a row trigger, the result set will have a single row.  For
	 * a statement trigger, this result set has every row that has
	 * changed or will change.  If a statement trigger does not affect 
	 * a row, then the result set will be empty (i.e. ResultSet.next()
	 * will return false).
	 *
	 * @return the ResultSet containing after images of the rows 
	 * changed by the triggering event.
	 *
	 * @exception SQLException if called after the triggering event has
	 * completed
	 */
	public java.sql.ResultSet getNewRowSet() throws SQLException
	{
		ensureProperContext();

		if (afterResultSet == null)
		{
			return null;
		}
		try
		{
			/* We should really shallow clone the result set, because it could be used
			 * at multiple places independently in trigger action.  This is a bug found
			 * during the fix of beetle 4373.
			 */
			CursorResultSet ars = afterResultSet;
			if (ars instanceof TemporaryRowHolderResultSet)
				ars = (CursorResultSet) ((TemporaryRowHolderResultSet) ars).clone();
			else if (ars instanceof TableScanResultSet)
				ars = (CursorResultSet) ((TableScanResultSet) ars).clone();
			ars.open();
			java.sql.ResultSet rs = cc.getResultSet(ars);
			resultSetVector.addElement(rs);
			return rs;
		} catch (StandardException se)
		{
			throw PublicAPI.wrapStandardException(se);
		}
	}

	/**
	 * Like getBeforeResultSet(), but returns a result set positioned
	 * on the first row of the before result set.  Used as a convenience
	 * to get a column for a row trigger.  Equivalent to getBeforeResultSet()
	 * followed by next().
	 *
	 * @return the ResultSet positioned on the old row image.
	 *
	 * @exception SQLException if called after the triggering event has
	 * completed
	 */
	public java.sql.ResultSet getOldRow() throws SQLException
	{
		java.sql.ResultSet rs = getOldRowSet();
		if (rs != null)
			rs.next();
		
		return rs;
	}

	/**
	 * Like getAfterResultSet(), but returns a result set positioned
	 * on the first row of the before result set.  Used as a convenience
	 * to get a column for a row trigger.  Equivalent to getAfterResultSet()
	 * followed by next().
	 *
	 * @return the ResultSet positioned on the new row image.
	 *
	 * @exception SQLException if called after the triggering event has
	 * completed
	 */
	public java.sql.ResultSet getNewRow() throws SQLException
	{
		java.sql.ResultSet rs = getNewRowSet();
		if (rs != null)
			rs.next();
		return rs;
	}
	
	public Long getAutoincrementValue(String identity)
	{
		// first search the hashtable-- this represents the ai values generated
		// by this trigger.
		if (aiHT != null)
			{
                Long value = aiHT.get(identity);
				if (value != null)
					return value;
			}
		
		
		// If we didn't find it in the hashtable search in the counters which
		// represent values inherited by trigger from insert statements.
		if (aiCounters != null)
		{
            for (AutoincrementCounter aic : aiCounters)
			{
				if (identity.equals(aic.getIdentity()))
				{
					return aic.getCurrentValue();
				}
			}
		}
		
		// didn't find it-- return NULL.
		return null;
	}
	/**
     * Copy a map of auto increment values into the trigger
     * execution context hash table of auto increment values.
	 */
    @SuppressWarnings("UseOfObsoleteCollectionType")
	public void copyHashtableToAIHT(Map<String,Long> from)
	{
		if (from == null)
			return;
		if (aiHT == null)
			aiHT = new Hashtable<String,Long>();

		aiHT.putAll(from);
	}
		
	/** 
	 * Reset Autoincrement counters to the beginning or the end.
	 * 
	 * @param		begin		if True, reset the AutoincremnetCounter to the
	 *                          beginning-- used to reset the counters for the
	 * 							next trigger. If false, reset it to the end--
	 *                          this sets up the counter appropriately for a
	 *                          AFTER STATEMENT trigger.
	 */
	public void resetAICounters(boolean begin)
	{
		if (aiCounters == null)
			return;

		afterRow = null;

        for (AutoincrementCounter aic : aiCounters)
		{
			aic.reset(begin);
		}
	}	
	
	/**
     * Update auto increment counters from the last row inserted.
	 *
	 */
	public void updateAICounters() throws StandardException
	{
		if (aiCounters == null)
			return;

        for (AutoincrementCounter aic : aiCounters)
		{
			DataValueDescriptor dvd = afterRow.getColumn(aic.getColumnPosition());
			aic.update(dvd.getLong());
		}
	}


    @Override
	public String toString() {
		return triggerd.getName();
	}


}
