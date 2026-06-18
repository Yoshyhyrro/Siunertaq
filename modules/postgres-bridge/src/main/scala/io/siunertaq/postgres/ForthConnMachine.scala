package io.siunertaq.postgres

import cats.effect.{IO, Ref}

// ─── ForthConnMachine — cats Ref ベース接続スタックマシン ────────────────────
//
//  Ref[IO, ConnState] が「何を追跡するか」を担う。
//  Pekko ForthRegistrarActor が「いつ再接続するか」を担う。
//  二者の役割が明確に分離されている。
//
//  withConn の guarantee 節:
//    SQL 例外が飛んでも Busy のまま詰まらない。
//    Pekko の postRestart が connect を呼ぶので Closed への遷移は不要。

final class ForthConnMachine(
  stateRef: Ref[IO, ConnState],
  url:  String,
  user: String,
  pass: String
):

  // ── 接続 ─────────────────────────────────────────────────────────────────
  def connect: IO[Unit] =
    IO.blocking(java.sql.DriverManager.getConnection(url, user, pass))
      .flatMap(conn => stateRef.set(ConnState.Idle(conn)))

  // ── withConn: Idle → Busy(op) → Idle ─────────────────────────────────────
  //  guarantee で失敗時も必ず Idle に戻し、次のオペが詰まらないようにする。
  def withConn[A](op: String)(f: java.sql.Connection => IO[A]): IO[A] =
    stateRef.get.flatMap:
      case ConnState.Idle(conn) =>
        stateRef.set(ConnState.Busy(conn, op)) *>
        f(conn).guarantee(stateRef.set(ConnState.Idle(conn)))
      case ConnState.Busy(_, current) =>
        IO.raiseError(IllegalStateException(
          s"[FORTH] connection busy with '$current', cannot start '$op'"
        ))
      case ConnState.Closed =>
        IO.raiseError(IllegalStateException(
          s"[FORTH] connection closed, cannot start '$op'"
        ))

  // ── クローズ ─────────────────────────────────────────────────────────────
  def close: IO[Unit] =
    stateRef.getAndSet(ConnState.Closed).flatMap:
      case ConnState.Idle(conn)    => IO(conn.close())
      case ConnState.Busy(conn, _) => IO(conn.close())
      case ConnState.Closed        => IO.unit

  // ── berryPhaseAngle 相当の診断ビュー ─────────────────────────────────────
  //  PhantomCarabiner.berryPhaseAngle と同じ役割:
  //  外から参照可能な唯一のゲージ不変量。
  def phase: IO[String] = stateRef.get.map(_.toString)

object ForthConnMachine:
  def make(url: String, user: String, pass: String): IO[ForthConnMachine] =
    Ref.of[IO, ConnState](ConnState.Closed)
      .map(ForthConnMachine(_, url, user, pass))

  /** 環境変数から構築するファクトリ (SiunertaqBatchApp 用) */
  def fromEnv: IO[ForthConnMachine] = make(
    url  = sys.env.getOrElse("POSTGRES_URL",  "jdbc:postgresql://localhost:5432/siunertaq"),
    user = sys.env.getOrElse("POSTGRES_USER", "siunertaq"),
    pass = sys.env.getOrElse("POSTGRES_PASS", "")
  )
