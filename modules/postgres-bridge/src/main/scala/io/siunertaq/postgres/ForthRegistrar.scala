package io.siunertaq.postgres

import cats.effect.IO
import io.circe.Json

// ─── ForthRegistrar — PostgreSQL Forth 関数への型安全ラッパー ─────────────────
//
//  ForthConnMachine.withConn { conn => ForthRegistrar(conn).registerStep(...) }
//  という形で使う。conn の取得・返却は ForthConnMachine 側が責任を持つ。

final class ForthRegistrar(conn: java.sql.Connection):

  def registerStep(args: ForthOp.RegisterStepArgs): IO[String] =
    IO.blocking {
      val stmt = conn.prepareCall(
        "{ ? = call register_and_compile_step(?, ?, ?, ?::bsd_vertex, ?, ?::jsonb) }"
      )
      try
        stmt.registerOutParameter(1, java.sql.Types.VARCHAR)
        stmt.setString(2, args.jobName)
        stmt.setString(3, args.stepName)
        stmt.setString(4, args.effectTag)
        stmt.setString(5, args.targetVertex)
        stmt.setInt(6,    args.priority)
        stmt.setString(7, args.instructions.noSpaces)
        stmt.execute()
        stmt.getString(1)
      finally stmt.close()
    }

  def registerImaginaryPop(args: ForthOp.RegisterPopArgs): IO[String] =
    IO.blocking {
      val stmt = conn.prepareCall(
        "{ ? = call register_imaginary_pop(?,?,?,?,?,?,?,?,?,?) }"
      )
      try
        stmt.registerOutParameter(1, java.sql.Types.VARCHAR)
        stmt.setInt(2,    args.origS1); stmt.setInt(3, args.origS2); stmt.setInt(4, args.origS3)
        stmt.setInt(5,    args.regS1);  stmt.setInt(6, args.regS2);  stmt.setInt(7, args.regS3)
        stmt.setString(8, args.src);    stmt.setInt(9, args.srcPhase)
        stmt.setString(10, args.tgt);   stmt.setInt(11, args.tgtPhase)
        stmt.execute()
        stmt.getString(1)
      finally stmt.close()
    }
