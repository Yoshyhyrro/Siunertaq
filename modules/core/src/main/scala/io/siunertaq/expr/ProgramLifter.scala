package io.siunertaq.expr

// ─── ProgramLifter ────────────────────────────────────────────────────────────
//
//  Lowering の逆変換: 平坦な Program (スタックマシン命令列) を
//  Expr ツリー → TypedExpr[T] (GADT) へと持ち上げる。
//
//  「GADTと関数が一致する状態」の核心:
//    TypedExpr[Ty.Scalar.type]  →  TypedParser[Ty.Scalar.type].parse が選ばれる
//    TypedExpr[Ty.Vec3.type]    →  TypedParser[Ty.Vec3.type].parse   が選ばれる
//
//  型パラメータ T が parse / perlPrintExpr を compile-time に決定する。
//  新しい Ty variant を追加すると given TypedParser[NewTy] が必要になり、
//  コンパイラが missing given として報告する (型安全な拡張性)。
//
//  ラウンドトリップ不変量:
//    liftProgram(lower(e)) == Right(e)   (well-typed な Expr e に対して)
//    — LoweringSpec の `exec(lower(e)) == eval(e)` と合わせて全体の整合性を保証

object ProgramLifter:

  // ─── §1  Program → Expr ────────────────────────────────────────────────────
  //
  //  スタックマシンを「式ツリーのスタック」で模擬する。
  //
  //  スタック順序の根拠 (Lowering.lowerUnchecked より):
  //    lower(Add(l, r)) = lower(l) ++ lower(r) :+ AddScalar
  //  実行後スタックは [r_expr, l_expr, ...] の順になるため、
  //  AddScalar は top=r, next=l を pop して Add(l, r) を再構築する。

  def liftProgram(program: Program): Either[String, Expr] =
    val exprStack = scala.collection.mutable.ArrayStack[Expr]()

    val stepResult = program.foldLeft[Either[String, Unit]](Right(())) { (acc, instr) =>
      acc.flatMap { _ =>
        instr match
          // ── プッシュ ─────────────────────────────────────────────────────
          case Instr.PushScalar(n) =>
            exprStack.push(Expr.ConstScalar(n)); Right(())

          case Instr.PushVec3(x, y, z) =>
            exprStack.push(Expr.ConstVec3(x, y, z)); Right(())

          // ── 二項演算: top=r, next=l → pop順に (r, l) を取り Add(l, r) ────
          case Instr.AddScalar | Instr.AddVec3 =>
            if exprStack.size < 2 then
              Left(s"$instr: stack underflow (depth ${exprStack.size})")
            else
              val r = exprStack.pop()
              val l = exprStack.pop()
              exprStack.push(Expr.Add(l, r)); Right(())

          case Instr.MulScalar =>
            if exprStack.size < 2 then Left("MulScalar: stack underflow")
            else
              val r = exprStack.pop()
              val l = exprStack.pop()
              exprStack.push(Expr.Mul(l, r)); Right(())

          case Instr.DotVec3 =>
            if exprStack.size < 2 then Left("DotVec3: stack underflow")
            else
              val r = exprStack.pop()
              val l = exprStack.pop()
              exprStack.push(Expr.Dot(l, r)); Right(())
      }
    }

    stepResult.flatMap { _ =>
      if exprStack.size == 1 then Right(exprStack.pop())
      else Left(s"liftProgram: ${exprStack.size} expression(s) remain on stack (expected 1)")
    }

  // ─── §2  TypedResult — GADT 型パラメータの存在型ラッパー ──────────────────
  //
  //  TypedExpr[T] の T を直接返せないため (型消去の壁)、
  //  sealed trait でラップして caller 側が match で T を確定する。
  //
  //  Scala 3 の exhaustiveness チェックにより、match の網羅性が保証される。

  sealed trait TypedResult derives CanEqual
  final case class ScalarTyped(expr: TypedExpr[Ty.Scalar.type]) extends TypedResult
  final case class Vec3Typed(expr: TypedExpr[Ty.Vec3.type])     extends TypedResult

  // ─── §3  Expr → TypedExpr (構造的持ち上げ) ─────────────────────────────────
  //
  //  ExprTyping.typeOf でウェルタイプ確認済みのみ呼ばれるため、
  //  Left ブランチはバグ時のみ到達する。

  def toTypedScalar(expr: Expr): Either[String, TypedExpr[Ty.Scalar.type]] =
    expr match
      case Expr.ConstScalar(n) =>
        Right(TypedExpr.TConstScalar(n))

      case Expr.Add(l, r) =>
        for
          tl <- toTypedScalar(l)
          tr <- toTypedScalar(r)
        yield TypedExpr.TAdd[Ty.Scalar.type](tl, tr)

      case Expr.Mul(l, r) =>
        for
          tl <- toTypedScalar(l)
          tr <- toTypedScalar(r)
        yield TypedExpr.TMul(tl, tr)

      case Expr.Dot(l, r) =>
        for
          tl <- toTypedVec3(l)
          tr <- toTypedVec3(r)
        yield TypedExpr.TDot(tl, tr)

      case other =>
        Left(s"toTypedScalar: $other は Scalar として持ち上げられない (型不一致)")

  def toTypedVec3(expr: Expr): Either[String, TypedExpr[Ty.Vec3.type]] =
    expr match
      case Expr.ConstVec3(x, y, z) =>
        Right(TypedExpr.TConstVec3(x, y, z))

      case Expr.Add(l, r) =>
        for
          tl <- toTypedVec3(l)
          tr <- toTypedVec3(r)
        yield TypedExpr.TAdd[Ty.Vec3.type](tl, tr)

      case other =>
        Left(s"toTypedVec3: $other は Vec3 として持ち上げられない (型不一致)")

  // ─── §4  完全パイプライン: Program → TypedResult ─────────────────────────

  def liftTyped(program: Program): Either[String, TypedResult] =
    for
      expr   <- liftProgram(program)
      ty     <- ExprTyping.typeOf(expr)
      result <- ty match
                  case Ty.Scalar => toTypedScalar(expr).map(ScalarTyped.apply)
                  case Ty.Vec3   => toTypedVec3(expr).map(Vec3Typed.apply)
    yield result

  // ─── §5  TypedParser typeclass — GADTと関数が一致する実装 ─────────────────
  //
  //  TypedExpr[T] の T が以下の2関数を compile-time に選択する:
  //    parse(output)     : Perl/外部プロセスの文字列出力 → Value
  //    perlPrintExpr     : Perl コードの print 文テンプレート
  //
  //  これが「GADTと関数が一致」の本体。
  //  Ty.Scalar → ScalarParser (1整数を読む)
  //  Ty.Vec3   → Vec3Parser   ("x y z" を読む)
  //  新しい Ty を追加 → given TypedParser[NewTy] を追加しないとコンパイルエラー。

  trait TypedParser[T <: Ty]:
    /** 外部プロセス (Perl 等) の stdout を Value に変換する。*/
    def parse(output: String): Either[String, Value]
    /** Perl のスタック最上位要素を標準出力するコード断片。*/
    def perlPrintExpr: String

  given TypedParser[Ty.Scalar.type] with
    def parse(output: String): Either[String, Value] =
      output.trim.toIntOption
        .map(ScalarValue.apply)
        .toRight(s"Scalar: Perl の出力が整数でない: '${output.trim}'")

    def perlPrintExpr: String =
      """print $stack[-1], "\n";"""

  given TypedParser[Ty.Vec3.type] with
    def parse(output: String): Either[String, Value] =
      output.trim.split("""\s+""") match
        case Array(xs, ys, zs) =>
          for
            x <- xs.toIntOption.toRight(s"Vec3.x が整数でない: '$xs'")
            y <- ys.toIntOption.toRight(s"Vec3.y が整数でない: '$ys'")
            z <- zs.toIntOption.toRight(s"Vec3.z が整数でない: '$zs'")
          yield Vec3Value(x, y, z)
        case tokens =>
          Left(s"Vec3: 'x y z' 形式でない (${tokens.length}トークン): '${output.trim}'")

    def perlPrintExpr: String =
      // Vec3 は Perl の arrayref [x, y, z] として表現される
      """{ my $v = $stack[-1]; print $v->[0], " ", $v->[1], " ", $v->[2], "\n"; }"""

  // ─── §6  TypedResult を使った型安全ディスパッチ ─────────────────────────
  //
  //  TypedResult の match が ScalarTyped か Vec3Typed かを決定し、
  //  対応する TypedParser[T] が summon される。
  //  コンパイラは両ケースの given が存在することを保証する。

  def parseTypedOutput(output: String, result: TypedResult): Either[String, Value] =
    result match
      case ScalarTyped(_) => summon[TypedParser[Ty.Scalar.type]].parse(output)
      case Vec3Typed(_)   => summon[TypedParser[Ty.Vec3.type]].parse(output)

  def perlPrintFor(result: TypedResult): String =
    result match
      case ScalarTyped(_) => summon[TypedParser[Ty.Scalar.type]].perlPrintExpr
      case Vec3Typed(_)   => summon[TypedParser[Ty.Vec3.type]].perlPrintExpr
