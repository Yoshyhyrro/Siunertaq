package io.siunertaq.postgres

import io.circe.{Encoder, Json}
import io.circe.syntax.*

// ─── MecrispInstr — Mecrisp-Stellaris Forth instruction ADT ──────────────────
//
//  Mirrors MecrispStellarisSchema.dhall §2.
//  Scala 3 enum with structured cases; derives circe JSON encoding for
//  insertion into PostgreSQL (JSONB) and ClickHouse (JSONEachRow).
//
//  Stack effect notation used in comments: ( before -- after )
//  Mecrisp-Stellaris reference: https://mecrisp-stellaris-folkdoc.sourceforge.io/

enum MecrispInstr derives CanEqual:
  // ── Literals ──────────────────────────────────────────────────────────────
  case Literal(n: Int)                            // n          ( -- n )
  case CharLit(c: Char)                           // 'c'        ( -- c )

  // ── Arithmetic ────────────────────────────────────────────────────────────
  case Plus                                       // +          ( a b -- a+b )
  case Minus                                      // -          ( a b -- a-b )
  case Multiply                                   // *          ( a b -- a*b )
  case Divide                                     // /          ( a b -- a/b )
  case DivMod                                     // /mod       ( a b -- rem quot )
  case Modulo                                     // mod        ( a b -- a mod b )
  case Negate                                     // negate     ( a -- -a )
  case Abs                                        // abs        ( a -- |a| )
  case MaxOp                                      // max        ( a b -- max(a,b) )
  case MinOp                                      // min        ( a b -- min(a,b) )
  case OnePlus                                    // 1+         ( a -- a+1 )
  case OneMinus                                   // 1-         ( a -- a-1 )
  case TwoMul                                     // 2*         ( a -- 2a )
  case TwoDiv                                     // 2/         ( a -- a/2 )

  // ── Bitwise ───────────────────────────────────────────────────────────────
  case And                                        // and        ( a b -- a&b )
  case Or                                         // or         ( a b -- a|b )
  case Xor                                        // xor        ( a b -- a^b )
  case Invert                                     // invert     ( a -- ~a )
  case LShift                                     // lshift     ( a n -- a<<n )
  case RShift                                     // rshift     ( a n -- a>>n )
  case URShift                                    // urshift    ( a n -- a>>>n )

  // ── Stack operations ──────────────────────────────────────────────────────
  case Dup                                        // dup        ( a -- a a )
  case Drop                                       // drop       ( a -- )
  case Swap                                       // swap       ( a b -- b a )
  case Over                                       // over       ( a b -- a b a )
  case Rot                                        // rot        ( a b c -- b c a )
  case NRot                                       // -rot       ( a b c -- c a b )
  case Dup2                                       // 2dup       ( a b -- a b a b )
  case Drop2                                      // 2drop      ( a b -- )
  case Tuck                                       // tuck       ( a b -- b a b )
  case Nip                                        // nip        ( a b -- b )
  case Pick(n: Int)                               // n pick     ( ... a -- ... a a )
  case Depth                                      // depth      ( -- n )

  // ── Return stack ──────────────────────────────────────────────────────────
  case ToR                                        // >r         ( a -- ) [R: a]
  case FromR                                      // r>         ( -- a ) [R: ]
  case RFetch                                     // r@         ( -- a ) copy top of R

  // ── Memory ────────────────────────────────────────────────────────────────
  case Store                                      // !          ( val addr -- )
  case Fetch                                      // @          ( addr -- val )
  case CStore                                     // c!         ( byte addr -- )
  case CFetch                                     // c@         ( addr -- byte )
  case HStore                                     // h!         ( half addr -- )
  case HFetch                                     // h@         ( addr -- half )
  case PlusStore                                  // +!         ( n addr -- )
  case VariableRef(name: String)                  // varName    ( -- addr ) push variable address

  // ── Comparison (Mecrisp returns -1 true, 0 false) ──────────────────────
  case Equal                                      // =          ( a b -- flag )
  case NotEqual                                   // <>         ( a b -- flag )
  case LessThan                                   // <          ( a b -- flag ) signed
  case GreaterThan                                // >          ( a b -- flag ) signed
  case LessEq                                     // <=         ( a b -- flag )
  case GreaterEq                                  // >=         ( a b -- flag )
  case ULessThan                                  // u<         ( u1 u2 -- flag ) unsigned
  case UGreaterThan                               // u>         ( u1 u2 -- flag ) unsigned
  case ZeroEqual                                  // 0=         ( a -- flag )
  case ZeroLess                                   // 0<         ( a -- flag )
  case ZeroGreater                                // 0>         ( a -- flag )

  // ── Control flow ──────────────────────────────────────────────────────────
  case If                                         // if         [structural: opens IF block]
  case Else                                       // else
  case Then                                       // then       [closes IF block]
  case Begin                                      // begin
  case Until                                      // until      ( flag -- )  loop while flag=0
  case While                                      // while      ( flag -- )
  case Repeat                                     // repeat
  case Again                                      // again      infinite loop
  case Do                                         // do         ( limit start -- )
  case Loop                                       // loop       increment by 1
  case PlusLoop                                   // +loop      ( n -- )
  case Leave                                      // leave      exit DO..LOOP
  case I                                          // i          ( -- index )
  case J                                          // j          ( -- outer index )
  case Exit                                       // exit       early return from word

  // ── Word calls ────────────────────────────────────────────────────────────
  case Call(word: String)                         // call a named Forth word
  case Recurse                                    // call current word

  // ── I/O ───────────────────────────────────────────────────────────────────
  case Emit                                       // emit       ( c -- )
  case CR                                         // cr         ( -- )
  case Dot                                        // .          ( n -- )
  case DotS                                       // .s         ( -- )  print stack

  // ── Meta / docs ───────────────────────────────────────────────────────────
  case LineComment(text: String)                  // \ comment  (not emitted to binary)
  case BlockComment(text: String)                 // ( comment )

// ─── Companion: serialization and rendering ───────────────────────────────────

object MecrispInstr:

  /** Render to Mecrisp-Stellaris source text. */
  def toSource(instr: MecrispInstr): String = instr match
    case Literal(n)       => n.toString
    case CharLit(c)       => s"'$c'"
    case Plus             => "+"
    case Minus            => "-"
    case Multiply         => "*"
    case Divide           => "/"
    case DivMod           => "/mod"
    case Modulo           => "mod"
    case Negate           => "negate"
    case Abs              => "abs"
    case MaxOp            => "max"
    case MinOp            => "min"
    case OnePlus          => "1+"
    case OneMinus         => "1-"
    case TwoMul           => "2*"
    case TwoDiv           => "2/"
    case And              => "and"
    case Or               => "or"
    case Xor              => "xor"
    case Invert           => "invert"
    case LShift           => "lshift"
    case RShift           => "rshift"
    case URShift          => "urshift"
    case Dup              => "dup"
    case Drop             => "drop"
    case Swap             => "swap"
    case Over             => "over"
    case Rot              => "rot"
    case NRot             => "-rot"
    case Dup2             => "2dup"
    case Drop2            => "2drop"
    case Tuck             => "tuck"
    case Nip              => "nip"
    case Pick(n)          => s"$n pick"
    case Depth            => "depth"
    case ToR              => ">r"
    case FromR            => "r>"
    case RFetch           => "r@"
    case Store            => "!"
    case Fetch            => "@"
    case CStore           => "c!"
    case CFetch           => "c@"
    case HStore           => "h!"
    case HFetch           => "h@"
    case PlusStore        => "+!"
    case VariableRef(nm)  => nm
    case Equal            => "="
    case NotEqual         => "<>"
    case LessThan         => "<"
    case GreaterThan      => ">"
    case LessEq           => "<="
    case GreaterEq        => ">="
    case ULessThan        => "u<"
    case UGreaterThan     => "u>"
    case ZeroEqual        => "0="
    case ZeroLess         => "0<"
    case ZeroGreater      => "0>"
    case If               => "if"
    case Else             => "else"
    case Then             => "then"
    case Begin            => "begin"
    case Until            => "until"
    case While            => "while"
    case Repeat           => "repeat"
    case Again            => "again"
    case Do               => "do"
    case Loop             => "loop"
    case PlusLoop         => "+loop"
    case Leave            => "leave"
    case I                => "i"
    case J                => "j"
    case Exit             => "exit"
    case Call(w)          => w
    case Recurse          => "recurse"
    case Emit             => "emit"
    case CR               => "cr"
    case Dot              => "."
    case DotS             => ".s"
    case LineComment(t)   => s"\\ $t"
    case BlockComment(t)  => s"( $t )"

  /** Stack depth delta for each instruction (None = unknown / control flow). */
  def stackDelta(instr: MecrispInstr): Option[Int] = instr match
    case Literal(_) | CharLit(_) | VariableRef(_) | I | J | Depth | RFetch => Some(+1)
    case Drop | ToR | Store | CStore | HStore | PlusStore | Emit             => Some(-1)
    case Plus | Minus | Multiply | Divide | DivMod | Modulo |
         And | Or | Xor | LShift | RShift | URShift |
         Equal | NotEqual | LessThan | GreaterThan | LessEq | GreaterEq |
         ULessThan | UGreaterThan | MaxOp | MinOp                            => Some(-1)
    case Negate | Abs | OnePlus | OneMinus | TwoMul | TwoDiv |
         Invert | ZeroEqual | ZeroLess | ZeroGreater | Fetch | CFetch | HFetch => Some(0)
    case Dup | Over | Tuck | RFetch                                          => Some(+1)
    case Swap | Rot | NRot | Nip                                             => Some(0)
    case Dup2                                                                => Some(+2)
    case Drop2                                                               => Some(-2)
    case FromR                                                               => Some(+1)
    case CR | DotS | Exit | Recurse | Loop | PlusLoop | Leave | Again |
         Begin | Until | While | Repeat | LineComment(_) | BlockComment(_)   => Some(0)
    case Dot                                                                 => Some(-1)
    case If | Do                                                             => Some(-1)  // consumes flag/limits
    case Else | Then                                                         => Some(0)
    case Pick(n)                                                             => Some(+1)
    case Call(_)                                                             => None      // depends on word
    case DivMod                                                              => Some(0)   // consumes 2, pushes 2

  /** Token string for ClickHouse Array(String) storage. */
  def toToken(instr: MecrispInstr): String = toSource(instr)

  /** circe JSON encoder: each instruction as a JSON object with "type" discriminator. */
  given Encoder[MecrispInstr] = Encoder.instance { instr =>
    Json.obj(
      "token"       -> Json.fromString(toToken(instr)),
      "stack_delta" -> stackDelta(instr).fold(Json.Null)(Json.fromInt)
    )
  }

// ─── Compiled word: a full Mecrisp word definition ───────────────────────────

final case class MecrispWordDef(
  name:         String,          // Forth word name
  stackEffect:  String,          // "( a b -- c )" computed
  body:         Vector[MecrispInstr],
  calledWords:  Set[String],
  maxStackDepth: Int,
  hasDeadCode:  Boolean,
  sourceClass:  String,
  sourceMethod: String,
  sourceDesc:   String
):
  /** Render complete Mecrisp-Stellaris word definition. */
  def toSource: String =
    val bodyStr = body.map(MecrispInstr.toSource).mkString("\n  ")
    s": $name  $stackEffect\n  $bodyStr\n;"

  /** Token array for ClickHouse Array(String). */
  def bodyTokens: Vector[String] = body.map(MecrispInstr.toToken)

  /** All words called by this definition (for dead code graph). */
  def directCallees: Set[String] = body.collect { case MecrispInstr.Call(w) => w }.toSet

object MecrispWordDef:
  given Encoder[MecrispWordDef] = Encoder.instance { w =>
    Json.obj(
      "word_name"       -> w.name.asJson,
      "stack_effect"    -> w.stackEffect.asJson,
      "body_tokens"     -> w.bodyTokens.asJson,
      "called_words"    -> w.calledWords.toSeq.sorted.asJson,
      "max_stack_depth" -> w.maxStackDepth.asJson,
      "has_dead_code"   -> w.hasDeadCode.asJson,
      "source_class"    -> w.sourceClass.asJson,
      "source_method"   -> w.sourceMethod.asJson,
      "source_desc"     -> w.sourceDesc.asJson
    )
  }
