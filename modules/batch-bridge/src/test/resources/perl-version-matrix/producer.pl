#!/usr/bin/env perl
use strict;
use warnings;
use JSON::PP;

# producer.pl <version_label>
#
# Generates fixture JSON mirroring the shapes actually used in this
# codebase's StackInstr / MZV JSON (see Instr.toJson and
# ClickHouseSyncProtocol.PushMZVTriple). Run once per Perl version; every
# OTHER version's consumer_check_all.pl will read this file back.
#
# Scope note: only Int values and 0/1-encoded "booleans" are covered,
# because that's all the current pipeline (PushScalar/PushVec3) actually
# carries. No strings, no floats -- add fixtures here if/when those show
# up in Instr.

my $version_label = shift @ARGV or die "usage: producer.pl <version_label>\n";

my $fixtures = {
    # Instr.PushScalar-shaped: single tag key wrapping a single int field.
    push_scalar  => { PushScalar => { n => 42 } },

    # Instr.PushVec3-shaped: single tag key wrapping a 3-key int hash.
    # Key order in a Perl hash is NOT guaranteed (5.18+ randomises hash
    # iteration order) -- consumer must read by name, not position.
    push_vec3    => { PushVec3 => { x => 2, y => 4, z => 0 } },

    # Boundary check for 32-bit Int width (Instr.PushScalar wraps a JVM
    # Int, so real values shouldn't exceed this, but it's a cheap check
    # against unexpected IV-width assumptions on old Perl builds).
    boundary_int => { PushScalar => { n => 2147483647 } },

    # "boolean" exactly as this codebase encodes it today: a 0/1 integer,
    # NOT a JSON true/false literal (see is_convergent / was_regularized
    # in ClickHouseSyncProtocol.PushMZVTriple). This deliberately sidesteps
    # the JSON::PP::Boolean blessed-scalar-ref question -- verified here
    # as "not applicable", not just assumed.
    flag_true    => { was_regularized => 1 },
    flag_false   => { was_regularized => 0 },
};

my $fixtures_dir = $ENV{FIXTURES_DIR} // '/fixtures';
my $out_dir = "$fixtures_dir/out";
mkdir($out_dir) unless -d $out_dir;
my $out_path = "$out_dir/$version_label.json";
open my $out, '>', $out_path
    or die "cannot write $out_path: $!";
print $out encode_json($fixtures), "\n";
close $out;

print "produced $out_path (perl $^V)\n";
