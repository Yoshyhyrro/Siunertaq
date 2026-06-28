package Siunertaq::StackMachine;
use strict;
use warnings;
use JSON::PP;   # core module — no installation required (Perl 5.6+)

# ─── Siunertaq::StackMachine ─────────────────────────────────────────────────
#
#  Perl mirror of io.siunertaq.expr.Program stack machine.
#
#  execute_json accepts the JSON array produced by Program.toJson (Scala),
#  which is identical to ClassASTBridge.extractFromBytes output and the
#  PostgreSQL JSONB instructions column written by ForthRegistrar.registerStep.
#
#  JSON key format (must match Program.toJson and ClassASTBridge.opcodeToInstr):
#    [{"PushScalar": {"n": 5}}, {"AddScalar": {}}, ...]
#    {"PushVec3":   {"x": 1, "y": 2, "z": 3}}
#    {"AddVec3":    {}}
#    {"MulScalar":  {}}
#    {"DotVec3":    {}}
#
#  Constructor options:
#    out => \*STDOUT   (default)
#    out => $fh        (open scalar ref for testing: open my $fh, '>', \$buf)

sub new {
    my ($class, %opt) = @_;
    return bless {
        stack => [],
        out   => $opt{out} // \*STDOUT,
    }, $class;
}

# ─── execute_json ─────────────────────────────────────────────────────────────
#  Decode and execute a JSON instruction array.
#  Dies on unknown instruction keys (fail-fast for cross-validation).

sub execute_json {
    my ($self, $json_str) = @_;
    my $instrs = JSON::PP::decode_json($json_str);
    for my $instr (@$instrs) {
        if    (exists $instr->{PushScalar}) {
            push @{$self->{stack}}, $instr->{PushScalar}{n};
        }
        elsif (exists $instr->{PushVec3}) {
            my $v = $instr->{PushVec3};
            push @{$self->{stack}}, [$v->{x}, $v->{y}, $v->{z}];
        }
        elsif (exists $instr->{AddScalar}) { $self->_add_scalar() }
        elsif (exists $instr->{AddVec3})   { $self->_add_vec3()   }
        elsif (exists $instr->{MulScalar}) { $self->_mul_scalar() }
        elsif (exists $instr->{DotVec3})   { $self->_dot_vec3()   }
        else {
            die "Siunertaq::StackMachine: unknown instr: "
              . JSON::PP::encode_json($instr) . "\n";
        }
    }
    return $self;
}

# ─── Output ───────────────────────────────────────────────────────────────
#  print_scalar / print_vec3 throw the top of the stack to the output filehandle.
#  out is set in the constructor (default: STDOUT).

sub print_scalar {
    my $self = shift;
    print { $self->{out} } $self->{stack}[-1], "\n";
}

sub print_vec3 {
    my $self = shift;
    my $v = $self->{stack}[-1];
    print { $self->{out} } $v->[0], " ", $v->[1], " ", $v->[2], "\n";
}

# ─── Internal Operations ─────────────────────────────────────────────────────
#  pop order: r = top, l = next — opposite of Lowering.lowerUnchecked push order

sub _add_scalar {
    my $self = shift;
    my $r = pop @{$self->{stack}};
    my $l = pop @{$self->{stack}};
    push @{$self->{stack}}, $l + $r;
}

sub _add_vec3 {
    my $self = shift;
    my $r = pop @{$self->{stack}};
    my $l = pop @{$self->{stack}};
    push @{$self->{stack}},
      [$l->[0]+$r->[0], $l->[1]+$r->[1], $l->[2]+$r->[2]];
}

sub _mul_scalar {
    my $self = shift;
    my $r = pop @{$self->{stack}};
    my $l = pop @{$self->{stack}};
    push @{$self->{stack}}, $l * $r;
}

sub _dot_vec3 {
    my $self = shift;
    my $r = pop @{$self->{stack}};
    my $l = pop @{$self->{stack}};
    push @{$self->{stack}},
      $l->[0]*$r->[0] + $l->[1]*$r->[1] + $l->[2]*$r->[2];
}

1;