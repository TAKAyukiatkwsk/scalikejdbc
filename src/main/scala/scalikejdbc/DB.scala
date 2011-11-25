/*
 * Copyright 2011 Kazuhiro Sera
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package scalikejdbc

import java.sql.Connection
import java.lang.IllegalStateException
import scala.util.control.Exception._


object DB {

  private def ensureDBInstance(db: DB): Unit = {
    if (db == null) {
      throw new IllegalStateException(ErrorMessage.IMPLICIT_DB_INSTANCE_REQUIRED)
    }
  }

  def autoCommit[A](execution: DBSession => A)(implicit db: DB): A = {
    ensureDBInstance(db: DB)
    db.autoCommit(execution)
  }

  def withinTx[A](execution: DBSession => A)(implicit db: DB): A = {
    ensureDBInstance(db: DB)
    db.withinTx(execution)
  }

  def localTx[A](execution: DBSession => A)(implicit db: DB): A = {
    ensureDBInstance(db: DB)
    db.localTx(execution)
  }

}

/**
 * DB accessor
 */
class DB(conn: Connection) {

  def isTxNotActive = conn == null || conn.isClosed || conn.isReadOnly

  def isTxNotYetStarted = conn != null && conn.getAutoCommit

  def isTxAlreadyStarted = conn != null && !conn.getAutoCommit

  def newTx(conn: Connection): Tx = {
    if (isTxNotActive || isTxAlreadyStarted) {
      throw new IllegalStateException(ErrorMessage.CANNOT_START_A_NEW_TRANSACTION)
    }
    new Tx(conn)
  }

  def newTx: Tx = newTx(conn)

  def currentTx: Tx = {
    if (isTxNotActive || isTxNotYetStarted) {
      throw new IllegalStateException(ErrorMessage.TRANSACTION_IS_NOT_ACTIVE)
    }
    new Tx(conn)
  }

  def tx: Tx = {
    handling(classOf[IllegalStateException]) by {
      e => throw new IllegalStateException(
        "DB#tx is an alias of DB#currentTx. " +
          "You cannot call this API before beginning a transaction")
    } apply currentTx
  }

  def begin() = newTx.begin()

  def beginIfNotYet(): Unit = {
    catching(classOf[IllegalStateException]) opt {
      begin()
    }
  }

  def commit(): Unit = tx.commit()

  def rollback(): Unit = tx.rollback()

  def rollbackIfActive(): Unit = {
    catching(classOf[IllegalStateException]) opt {
      tx.rollbackIfActive()
    }
  }

  def readOnlySession(): DBSession = {
    conn.setReadOnly(true)
    new DBSession(conn)
  }

  def readOnly[A](execution: DBSession => A): A = {
    val session = readOnlySession()
    execution(session)
  }

  def autoCommitSession(): DBSession = new DBSession(conn)

  def autoCommit[A](execution: DBSession => A): A = {
    val session = autoCommitSession()
    execution(session)
  }

  def withinTxSession(tx: Tx = currentTx): DBSession = {
    if (!tx.isActive) {
      throw new IllegalStateException(ErrorMessage.TRANSACTION_IS_NOT_ACTIVE)
    }
    new DBSession(conn, Some(tx))
  }

  def withinTx[A](execution: DBSession => A): A = {
    val session = withinTxSession(currentTx)
    execution(session)
  }

  def localTx[A](execution: DBSession => A): A = {
    val tx = newTx
    tx.begin()
    if (!tx.isActive) {
      throw new IllegalStateException(ErrorMessage.TRANSACTION_IS_NOT_ACTIVE)
    }
    val session = new DBSession(conn, Some(tx))

    handling(classOf[Throwable]) by {
      e => {
        tx.rollback()
        throw e
      }
    } apply {
      val result = execution(session)
      tx.commit()
      result
    }
  }

}
