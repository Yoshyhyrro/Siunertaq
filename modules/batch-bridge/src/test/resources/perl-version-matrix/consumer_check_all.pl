#!/usr/bin/env perl
use strict;
use warnings;
use JSON::PP;

# consumer_check_all.pl <this_consumer_version_label>
#
# Reads every producer's fixture file under /fixtures/out/*.json and checks
# each value is still semantically correct from THIS Perl version's point
# of view -- not "same JSON text", but "still means the same thing".
# Exits non-zero if any producer/consumer version pair disagrees.

my $self             = shift @ARGV or die "usage: consumer_check_all.pl <version_label> <expected_producer_count>\n";
my $expected_count   = shift @ARGV or die "usage: consumer_check_all.pl <version_label> <expected_producer_count>\n";

sub check_num {
    my ($label, $got, $want) = @_;
    return "$label: got '" . (defined $got ? $got : 'undef') . "', want $want"
        unless defined($got) && $got == $want;
    return ();
}

my $fixtures_dir = $ENV{FIXTURES_DIR} // '/fixtures';
my @fixture_files = sort glob("$fixtures_dir/out/*.json");

# Zero files found must be a hard failure, not a vacuous "OK" -- otherwise
# a broken mount, a producer that silently failed, or a typo'd path would
# report success while checking nothing at all. Same failure class as the
# ClassASTBridge opcode issue earlier in this project: "ran, found
# nothing, declared victory" is worse than an outright error.
if (!@fixture_files) {
    print "FAIL (consumer=$self): no fixture files found under $fixtures_dir/out -- ",
          "did the producers run first?\n";
    exit 1;
}
if (@fixture_files != $expected_count) {
    print "FAIL (consumer=$self): found ", scalar(@fixture_files),
          " fixture file(s), expected $expected_count -- ",
          "did every producer actually write its file?\n";
    exit 1;
}

my @all_failures;
for my $file (@fixture_files) {
    my ($producer) = $file =~ m{/([^/]+)\.json$};

    open my $fh, '<', $file or do {
        push @all_failures, "$file: cannot open ($!)";
        next;
    };
    my $json_text = do { local $/; <$fh> };
    close $fh;

    my $data = eval { decode_json($json_text) };
    if (!$data) {
        push @all_failures, "$producer->$self: decode_json failed: $@";
        next;
    }

    # --- Priority 1: numeric fidelity (Int is the only value type today) --
    push @all_failures, check_num("$producer->$self push_scalar.n",
        $data->{push_scalar}{PushScalar}{n}, 42);
    push @all_failures, check_num("$producer->$self boundary_int.n",
        $data->{boundary_int}{PushScalar}{n}, 2147483647);
    push @all_failures, check_num("$producer->$self push_vec3.x",
        $data->{push_vec3}{PushVec3}{x}, 2);
    push @all_failures, check_num("$producer->$self push_vec3.y",
        $data->{push_vec3}{PushVec3}{y}, 4);
    push @all_failures, check_num("$producer->$self push_vec3.z",
        $data->{push_vec3}{PushVec3}{z}, 0);

    # --- Priority 2: hash key access must not depend on iteration order ---
    my @vec3_keys = sort keys %{ $data->{push_vec3}{PushVec3} || {} };
    push @all_failures, "$producer->$self push_vec3 keys unexpected: @vec3_keys"
        unless "@vec3_keys" eq "x y z";

    # --- Checked-but-currently-N/A: 0/1 "boolean" encoding ------------------
    # This codebase encodes booleans as 0/1 ints (see is_convergent /
    # was_regularized), not JSON true/false, so JSON::PP::Boolean's
    # blessed-scalar-ref representation never actually enters this
    # pipeline. Verified here as "not applicable" rather than assumed.
    push @all_failures, check_num("$producer->$self flag_true",
        $data->{flag_true}{was_regularized}, 1);
    push @all_failures, check_num("$producer->$self flag_false",
        $data->{flag_false}{was_regularized}, 0);

    # --- Deferred (no fixtures yet): UTF-8 strings, float precision --------
    # Add fixtures + checks here once Instr grows a string or Double case
    # (see the binary1024 / PushBigScalar discussion).
}

@all_failures = grep { defined $_ && length $_ } @all_failures;

if (@all_failures) {
    print "FAIL (consumer=$self):\n";
    print "  - $_\n" for @all_failures;
    exit 1;
}
print "OK (consumer=$self): all producer round-trips passed\n";
exit 0;
