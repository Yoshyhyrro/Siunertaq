{-|
  MecrispStellarisSchema.dhall
  modules/postgres-bridge/src/main/resources/MecrispStellarisSchema.dhall

  Strict Dhall type definitions for Mecrisp-Stellaris Forth instructions.
  Mecrisp-Stellaris: Forth implementation targeting ARM Cortex-M microcontrollers.
  https://mecrisp-stellaris-folkdoc.sourceforge.io/

  This schema bridges:
    JVM bytecode (via ClassASTBridge.scala / ASM)
      → MecrispInstr (typed here)
        → PostgreSQL compiled_words
          → ClickHouse bytecode_instructions (CDC)

  Stack effect notation: ( before -- after )
    a, b, c = any cell-sized value
    n       = integer literal
    addr    = memory address
    flag    = boolean (0 = false, non-zero = true; Mecrisp uses -1 = true)
    u       = unsigned integer
-}

-- ── §1  Primitive types ──────────────────────────────────────────────────────

-- A Mecrisp "cell" is 32 bits on Cortex-M0/M3/M4
let CellWidth : Natural = 32

-- Stack effect annotation: purely documentary, not enforced by Dhall
let StackEffect = Text   -- e.g. "( a b -- c )"

-- ── §2  Mecrisp-Stellaris instruction ADT ────────────────────────────────────

let MecrispInstr =
  -- §2a  Literal push (compile-time immediate)
  < Literal      : { n : Integer }                   -- n          ( -- n )
  | CharLiteral  : { c : Natural }                   -- ascii      ( -- c )

  -- §2b  Arithmetic (mirrors JVM IADD/ISUB/IMUL/IDIV)
  | Plus         : {}                                -- +          ( a b -- a+b )
  | Minus        : {}                                -- -          ( a b -- a-b )
  | Multiply     : {}                                -- *          ( a b -- a*b )
  | DivMod       : {}                                -- /mod       ( a b -- rem quot )
  | Divide       : {}                                -- /          ( a b -- a/b )
  | Modulo       : {}                                -- mod        ( a b -- a mod b )
  | Negate       : {}                                -- negate     ( a -- -a )
  | Abs          : {}                                -- abs        ( a -- |a| )
  | MaxOp        : {}                                -- max        ( a b -- max )
  | MinOp        : {}                                -- min        ( a b -- min )
  | OnePlus      : {}                                -- 1+         ( a -- a+1 )
  | OneMinus     : {}                                -- 1-         ( a -- a-1 )
  | TwoPlus      : {}                                -- 2+         ( a -- a+2 )
  | TwoMinus     : {}                                -- 2-         ( a -- a-2 )
  | TwoMul       : {}                                -- 2*         ( a -- a*2 )  lshift 1
  | TwoDiv       : {}                                -- 2/         ( a -- a/2 )  arithmetic rshift 1

  -- §2c  Bitwise (mirrors JVM IAND/IOR/IXOR/ISHL/ISHR/IUSHR)
  | And          : {}                                -- and        ( a b -- a&b )
  | Or           : {}                                -- or         ( a b -- a|b )
  | Xor          : {}                                -- xor        ( a b -- a^b )
  | Invert       : {}                                -- invert     ( a -- ~a )  NOT
  | LShift       : {}                                -- lshift     ( a n -- a<<n )
  | RShift       : {}                                -- rshift     ( a n -- a>>n )  arithmetic
  | URShift      : {}                                -- urshift    ( a n -- a>>>n ) unsigned

  -- §2d  Stack manipulation (JVM DUP/POP/SWAP/OVER etc.)
  | Dup          : {}                                -- dup        ( a -- a a )
  | Drop         : {}                                -- drop       ( a -- )
  | Swap         : {}                                -- swap       ( a b -- b a )
  | Over         : {}                                -- over       ( a b -- a b a )
  | Rot          : {}                                -- rot        ( a b c -- b c a )
  | NRot         : {}                                -- -rot       ( a b c -- c a b )
  | Dup2         : {}                                -- 2dup       ( a b -- a b a b )
  | Drop2        : {}                                -- 2drop      ( a b -- )
  | Swap2        : {}                                -- 2swap      ( a b c d -- c d a b )
  | Over2        : {}                                -- 2over      ( a b c d -- a b c d a b )
  | Tuck         : {}                                -- tuck       ( a b -- b a b )
  | Nip          : {}                                -- nip        ( a b -- b )
  | Pick         : { n : Natural }                   -- n pick     ( ... a ... -- ... a ... a )
  | Roll         : { n : Natural }                   -- n roll     ( ... a -- ... a moved )
  | Depth        : {}                                -- depth      ( -- n )  stack depth

  -- §2e  Return stack (JVM has no direct analog — used for loop counters)
  | ToR          : {}                                -- >r         ( a -- ) [R: a]
  | FromR        : {}                                -- r>         ( -- a ) [R: ]
  | RFetch       : {}                                -- r@         ( -- a ) [R: a] copy

  -- §2f  Memory (JVM ISTORE/ILOAD via local variables, or heap)
  | Store        : {}                                -- !          ( val addr -- )  cell store
  | Fetch        : {}                                -- @          ( addr -- val )  cell fetch
  | CStore       : {}                                -- c!         ( byte addr -- ) byte store
  | CFetch       : {}                                -- c@         ( addr -- byte ) byte fetch
  | HStore       : {}                                -- h!         ( half addr -- ) halfword store
  | HFetch       : {}                                -- h@         ( addr -- half ) halfword fetch
  | PlusStore    : {}                                -- +!         ( n addr -- )    add to memory
  | StoreAddr    : { addr : Natural }                -- lit addr ! inline store
  | FetchAddr    : { addr : Natural }                -- lit addr @ inline fetch

  -- §2g  Comparison → flags (-1 true, 0 false in Mecrisp)
  | Equal        : {}                                -- =          ( a b -- flag )
  | NotEqual     : {}                                -- <>         ( a b -- flag )
  | LessThan     : {}                                -- <          ( a b -- flag ) signed
  | GreaterThan  : {}                                -- >          ( a b -- flag ) signed
  | LessEq       : {}                                -- <=         ( a b -- flag )
  | GreaterEq    : {}                                -- >=         ( a b -- flag )
  | ULessThan    : {}                                -- u<         ( u1 u2 -- flag ) unsigned
  | UGreaterThan : {}                                -- u>         ( u1 u2 -- flag ) unsigned
  | ZeroEqual    : {}                                -- 0=         ( a -- flag )
  | ZeroLess     : {}                                -- 0<         ( a -- flag )
  | ZeroGreater  : {}                                -- 0>         ( a -- flag )

  -- §2h  Control flow (JVM IF_ICMPxx / GOTO / structured control)
  --       Mecrisp uses structured Forth; no raw GOTO
  | If           : {}                                -- if         [compile-time]
  | Else         : {}                                -- else       [compile-time]
  | Then         : {}                                -- then       [compile-time]
  | Begin        : {}                                -- begin      [compile-time]
  | Until        : {}                                -- until      [compile-time] loop while flag=0
  | While        : {}                                -- while      [compile-time]
  | Repeat       : {}                                -- repeat     [compile-time]
  | Again        : {}                                -- again      [compile-time] infinite loop
  | Do           : {}                                -- do         ( limit start -- ) [R: limit start]
  | Loop         : {}                                -- loop       increment index by 1
  | PlusLoop     : {}                                -- +loop      ( n -- ) increment by n
  | Leave        : {}                                -- leave      exit do..loop early
  | I            : {}                                -- i          ( -- index ) current loop index
  | J            : {}                                -- j          ( -- index ) outer loop index

  -- §2i  Word calls and definitions
  | Call         : { word : Text }                   -- call named Forth word
  | Recurse      : {}                                -- recurse    call current word recursively
  | Exit         : {}                                -- exit       early return from word

  -- §2j  I/O (Mecrisp-Stellaris serial UART)
  | Emit         : {}                                -- emit       ( c -- )  send char
  | Key          : {}                                -- key        ( -- c )  receive char
  | CR           : {}                                -- cr         ( -- )    carriage return
  | Space        : {}                                -- space      ( -- )    emit space
  | Dot          : {}                                -- .          ( n -- )  print integer
  | UDot         : {}                                -- u.         ( u -- )  print unsigned
  | DotS         : {}                                -- .s         ( -- )    print stack

  -- §2k  Mecrisp-Stellaris extensions (ARM Cortex-M specific)
  | Hlt          : {}                                -- hlt        halt the processor
  | Reset        : {}                                -- reset      software reset
  | MSec         : {}                                -- ms         ( n -- ) delay n milliseconds
  | Ticks        : {}                                -- ticks      ( -- n ) system tick counter

  -- §2l  Meta / compilation artifacts
  | Comment      : { text : Text }                   -- \  ( -- )  line comment (not emitted)
  | Constant     : { name : Text, value : Integer }  -- name constant n
  | Variable     : { name : Text }                   -- name variable
  | Allot        : { cells : Natural }               -- n allot    reserve n cells
  >

-- ── §3  Complete word definition ────────────────────────────────────────────

let WordDef =
  { name         : Text              -- Forth word name (Mecrisp allows any non-whitespace)
  , stack_effect : StackEffect       -- ( before -- after ) documentary
  , body         : List MecrispInstr -- instruction sequence
  , is_inline    : Bool              -- inline: folded at call site (no CALL overhead)
  , source_class : Text              -- originating JVM class
  , source_method: Text              -- originating JVM method
  , source_desc  : Text              -- JVM method descriptor
  }

-- ── §4  Compilation unit ─────────────────────────────────────────────────────

let CompilationUnit =
  { class_name  : Text
  , source_file : Text           -- .class file path
  , words       : List WordDef
  , constants   : List { name : Text, value : Integer }
  , variables   : List { name : Text, initial_value : Optional Integer }
  }

-- ── §5  Opcode → Mecrisp mapping table (documentation) ──────────────────────
--
--  JVM opcode  → Mecrisp word(s)           Stack effect change
--  ──────────────────────────────────────────────────────────────
--  ICONST_n    → Literal n                  +1
--  BIPUSH n    → Literal n                  +1
--  SIPUSH n    → Literal n                  +1
--  IADD        → Plus                       -1
--  ISUB        → Minus                      -1
--  IMUL        → Multiply                   -1
--  IDIV        → Divide                     -1
--  IREM        → Modulo                     -1
--  INEG        → Negate                      0
--  IAND        → And                        -1
--  IOR         → Or                         -1
--  IXOR        → Xor                        -1
--  ISHL        → LShift                     -1
--  ISHR        → RShift                     -1
--  IUSHR       → URShift                    -1
--  DUP         → Dup                        +1
--  DUP_X1      → Over Rot / Tuck            +1 (complex)
--  POP         → Drop                       -1
--  SWAP        → Swap                        0
--  DUP2        → Dup2                       +2
--  POP2        → Drop2                      -2
--  ISTORE_n    → StoreAddr (var slot n)     -1
--  ILOAD_n     → FetchAddr (var slot n)     +1
--  IINC n d    → FetchAddr n Literal d Plus StoreAddr n  0
--  IF_ICMPEQ   → Equal If ... Then          -2 per branch
--  IFEQ        → ZeroEqual If ... Then      -1 per branch
--  GOTO (fwd)  → (structured: begin/again)   0
--  IRETURN     → Exit                       -1 (return value stays)
--  RETURN      → (end of word)               0

-- ── §6  Export ───────────────────────────────────────────────────────────────

in  { MecrispInstr  = MecrispInstr
    , WordDef       = WordDef
    , CompilationUnit = CompilationUnit
    , CellWidth     = CellWidth
    }
