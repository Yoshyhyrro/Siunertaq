package io.siunertaq.postgres

enum ConnState derives CanEqual:
  case Closed
  case Idle(conn: java.sql.Connection)
  case Busy(conn: java.sql.Connection, op: String)