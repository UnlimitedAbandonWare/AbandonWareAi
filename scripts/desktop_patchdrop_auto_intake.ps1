[CmdletBinding()]
param(
    [string]$PolicyPath = 'C:\ProgramData\AbandonWareX\desktop_patchdrop_auto_intake.policy.json',
    [string]$Root = '',
    [string]$StateRoot = 'C:\ProgramData\AbandonWareX\desktop-patchdrop-auto-intake',
    [string]$EvidencePath = '',
    [string]$VerificationEvidencePath = '',
    [string]$AdapterPath = '',
    [int]$AdapterTimeoutMilliseconds = 0,
    [switch]$TestMode
)

Set-StrictMode -Version Latest

$script:AwxAutoIntakeRequiredFields = @(
    'schemaVersion','enabled','mode','expectedBackingShareIdentitySha256',
    'expectedShareAclSha256','allowedNodes','allowedTopics','allowedPathPrefixes',
    'maxPatchBytes','maxChangedFiles','maxHunks','pollIntervalMinutes',
    'verificationProfile'
)
$script:AwxAutoIntakePolicySchema = 'awx.desktop-patchdrop-auto-intake.policy.v1'
$script:AwxAutoIntakeMaxPolicyBytes = 65536
$script:AwxAutoIntakeMaxManifestBytes = 65536
$script:AwxAutoIntakeMaxAllowlistEntries = 64
$script:AwxAutoIntakeApplyEvidenceFields = @(
    'canonicalRootProven','backingShareIdentityVerified','shareAclHashMatch',
    'indexLock','activeLeaseCount','dirtyOverlapCount','preimageMatch',
    'secretPatternHitCount','sourceIsolationPass','patchBytes','changedFileCount',
    'hunkCount','rollbackReady'
)
$script:AwxAutoIntakeDecisionFields = @('decision','failureClass','mode','patchName','bundleIdHash','queueCount','validatorVersion')
$script:AwxAutoIntakeMaxLedgerRowBytes = 4096
$script:AwxAutoIntakeMaxLedgerRows = 256
$script:AwxAutoIntakeMaxStateBytes = 65536
$script:AwxAutoIntakeMaxAdapterOutputBytes = 16384
$script:AwxAutoIntakeTaskExecutionLimitMilliseconds = 15 * 60 * 1000
$script:AwxAutoIntakeAdapterCleanupMilliseconds = 30 * 1000
$script:AwxAutoIntakeAdapterSafetyMarginMilliseconds = 30 * 1000
$script:AwxAutoIntakeAdapterTimeoutMilliseconds = $script:AwxAutoIntakeTaskExecutionLimitMilliseconds - $script:AwxAutoIntakeAdapterCleanupMilliseconds - $script:AwxAutoIntakeAdapterSafetyMarginMilliseconds
$script:AwxAutoIntakeMaxAdapterPatchBytes = 67108864
$script:AwxAutoIntakeMaxLedgerBytes = $script:AwxAutoIntakeMaxLedgerRows * ($script:AwxAutoIntakeMaxLedgerRowBytes + 1)
$script:AwxAutoIntakeProcessedStateSchema = 'awx.desktop-patchdrop-auto-intake.processed.v2'
$script:AwxAutoIntakeContractProbeHandleAllowlist = $false
$script:AwxAutoIntakeContractInjectAssignmentFailure = $false
$script:AwxAutoIntakeContractInjectBackupCleanupFailure = $false
$script:AwxAutoIntakeContractOverridePowerShellPath = ''
$script:AwxAutoIntakeAdapterProvenance = [System.Runtime.CompilerServices.ConditionalWeakTable[object,object]]::new()
$script:AwxAutoIntakeRunEvidenceSchema = 'awx.desktop-patchdrop-auto-intake.run-evidence.v1'
$script:AwxAutoIntakeVerificationSchema = 'awx.desktop-patchdrop-auto-intake.verification.v1'
$script:AwxAutoIntakeOutcomeSchema = 'awx.desktop-patchdrop-auto-intake.outcome.v1'
$script:AwxAutoIntakeOutcomeFields = @(
    'schemaVersion','runIdHash','bundleIdHash','mode','decision','failureClass','patchName',
    'queueCount','secretPatternHitCount','leaseConflict','dirtyOverlap','preimageMatch',
    'verificationPassed','rollbackReady','rollbackResult','elapsedMs','desktopFinalProof'
)

function Initialize-AwxAutoIntakeJobRunner {
    if ('AwxAutoIntakeJobRunner' -as [type]) { return }
    Add-Type -TypeDefinition @'
using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

public sealed class AwxAutoIntakeJobRunResult
{
    public int ExitCode = -1;
    public bool TimedOut;
    public bool OutputOverflow;
    public bool AssignmentSucceeded;
    public bool Resumed;
    public bool TreeExited;
    public bool HandleAllowlistProven;
    public bool AttributeSizingProbeProven;
    public bool SentinelInherited;
    public bool RootTerminationAttempted;
    public bool RootTerminationSucceeded;
    public int RootProcessId;
    public string Failure = "";
    public byte[] Stdout = new byte[0];
    public byte[] Stderr = new byte[0];
}

public static class AwxAutoIntakeJobRunner
{
    private const uint JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000;
    private const int JobObjectBasicAccountingInformation = 1;
    private const int JobObjectExtendedLimitInformation = 9;
    private const uint CREATE_SUSPENDED = 0x00000004;
    private const uint CREATE_NO_WINDOW = 0x08000000;
    private const uint EXTENDED_STARTUPINFO_PRESENT = 0x00080000;
    private static readonly IntPtr PROC_THREAD_ATTRIBUTE_HANDLE_LIST = new IntPtr(0x00020002);
    private const uint STARTF_USESTDHANDLES = 0x00000100;
    private const uint HANDLE_FLAG_INHERIT = 0x00000001;
    private const uint GENERIC_READ = 0x80000000;
    private const uint FILE_SHARE_READ = 0x00000001;
    private const uint FILE_SHARE_WRITE = 0x00000002;
    private const uint OPEN_EXISTING = 3;
    private const uint WAIT_OBJECT_0 = 0;
    private const uint WAIT_TIMEOUT = 258;
    private const uint STILL_ACTIVE = 259;
    private const int ERROR_INSUFFICIENT_BUFFER = 122;
    private static readonly IntPtr InvalidHandleValue = new IntPtr(-1);

    [StructLayout(LayoutKind.Sequential)]
    private struct SECURITY_ATTRIBUTES
    {
        public int nLength;
        public IntPtr lpSecurityDescriptor;
        [MarshalAs(UnmanagedType.Bool)] public bool bInheritHandle;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct STARTUPINFO
    {
        public int cb;
        public string lpReserved;
        public string lpDesktop;
        public string lpTitle;
        public int dwX;
        public int dwY;
        public int dwXSize;
        public int dwYSize;
        public int dwXCountChars;
        public int dwYCountChars;
        public int dwFillAttribute;
        public int dwFlags;
        public short wShowWindow;
        public short cbReserved2;
        public IntPtr lpReserved2;
        public IntPtr hStdInput;
        public IntPtr hStdOutput;
        public IntPtr hStdError;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct STARTUPINFOEX
    {
        public STARTUPINFO StartupInfo;
        public IntPtr lpAttributeList;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct PROCESS_INFORMATION
    {
        public IntPtr hProcess;
        public IntPtr hThread;
        public uint dwProcessId;
        public uint dwThreadId;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct JOBOBJECT_BASIC_LIMIT_INFORMATION
    {
        public long PerProcessUserTimeLimit;
        public long PerJobUserTimeLimit;
        public uint LimitFlags;
        public UIntPtr MinimumWorkingSetSize;
        public UIntPtr MaximumWorkingSetSize;
        public uint ActiveProcessLimit;
        public UIntPtr Affinity;
        public uint PriorityClass;
        public uint SchedulingClass;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct IO_COUNTERS
    {
        public ulong ReadOperationCount;
        public ulong WriteOperationCount;
        public ulong OtherOperationCount;
        public ulong ReadTransferCount;
        public ulong WriteTransferCount;
        public ulong OtherTransferCount;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct JOBOBJECT_EXTENDED_LIMIT_INFORMATION
    {
        public JOBOBJECT_BASIC_LIMIT_INFORMATION BasicLimitInformation;
        public IO_COUNTERS IoInfo;
        public UIntPtr ProcessMemoryLimit;
        public UIntPtr JobMemoryLimit;
        public UIntPtr PeakProcessMemoryUsed;
        public UIntPtr PeakJobMemoryUsed;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct JOBOBJECT_BASIC_ACCOUNTING_INFORMATION
    {
        public long TotalUserTime;
        public long TotalKernelTime;
        public long ThisPeriodTotalUserTime;
        public long ThisPeriodTotalKernelTime;
        public uint TotalPageFaultCount;
        public uint TotalProcesses;
        public uint ActiveProcesses;
        public uint TotalTerminatedProcesses;
    }

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr CreateJobObject(IntPtr jobAttributes, string name);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetInformationJobObject(IntPtr job, int infoClass, IntPtr info, uint infoLength);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool QueryInformationJobObject(IntPtr job, int infoClass, IntPtr info, uint infoLength, IntPtr returnLength);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool TerminateJobObject(IntPtr job, uint exitCode);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CreatePipe(out IntPtr readPipe, out IntPtr writePipe, ref SECURITY_ATTRIBUTES attributes, uint size);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetHandleInformation(IntPtr handle, uint mask, uint flags);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr CreateFile(string fileName, uint desiredAccess, uint shareMode, ref SECURITY_ATTRIBUTES attributes, uint creationDisposition, uint flags, IntPtr templateFile);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CreateProcess(string applicationName, StringBuilder commandLine, IntPtr processAttributes, IntPtr threadAttributes, [MarshalAs(UnmanagedType.Bool)] bool inheritHandles, uint creationFlags, IntPtr environment, string currentDirectory, ref STARTUPINFOEX startupInfo, out PROCESS_INFORMATION processInformation);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool InitializeProcThreadAttributeList(IntPtr attributeList, int attributeCount, int flags, ref IntPtr size);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool UpdateProcThreadAttribute(IntPtr attributeList, uint flags, IntPtr attribute, IntPtr value, IntPtr size, IntPtr previousValue, IntPtr returnSize);

    [DllImport("kernel32.dll")]
    private static extern void DeleteProcThreadAttributeList(IntPtr attributeList);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern uint ResumeThread(IntPtr thread);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool PeekNamedPipe(IntPtr pipe, IntPtr buffer, uint bufferSize, IntPtr bytesRead, out uint totalBytesAvailable, IntPtr bytesLeftThisMessage);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool ReadFile(IntPtr handle, byte[] buffer, uint bytesToRead, out uint bytesRead, IntPtr overlapped);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetExitCodeProcess(IntPtr process, out uint exitCode);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool TerminateProcess(IntPtr process, uint exitCode);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CloseHandle(IntPtr handle);

    private static void Close(ref IntPtr handle)
    {
        if (handle != IntPtr.Zero && handle != InvalidHandleValue)
        {
            CloseHandle(handle);
            handle = IntPtr.Zero;
        }
    }

    private static bool ConfigureKillOnClose(IntPtr job)
    {
        JOBOBJECT_EXTENDED_LIMIT_INFORMATION value = new JOBOBJECT_EXTENDED_LIMIT_INFORMATION();
        value.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        int size = Marshal.SizeOf(typeof(JOBOBJECT_EXTENDED_LIMIT_INFORMATION));
        IntPtr buffer = Marshal.AllocHGlobal(size);
        try
        {
            Marshal.StructureToPtr(value, buffer, false);
            return SetInformationJobObject(job, JobObjectExtendedLimitInformation, buffer, (uint)size);
        }
        finally { Marshal.FreeHGlobal(buffer); }
    }

    private static bool TryGetActiveProcesses(IntPtr job, out uint active)
    {
        active = UInt32.MaxValue;
        int size = Marshal.SizeOf(typeof(JOBOBJECT_BASIC_ACCOUNTING_INFORMATION));
        IntPtr buffer = Marshal.AllocHGlobal(size);
        try
        {
            if (!QueryInformationJobObject(job, JobObjectBasicAccountingInformation, buffer, (uint)size, IntPtr.Zero)) return false;
            JOBOBJECT_BASIC_ACCOUNTING_INFORMATION value = (JOBOBJECT_BASIC_ACCOUNTING_INFORMATION)Marshal.PtrToStructure(buffer, typeof(JOBOBJECT_BASIC_ACCOUNTING_INFORMATION));
            active = value.ActiveProcesses;
            return true;
        }
        finally { Marshal.FreeHGlobal(buffer); }
    }

    private static bool DrainPipe(IntPtr pipe, MemoryStream sink, int maximum, byte[] buffer, out bool overflow)
    {
        overflow = false;
        while (true)
        {
            uint available;
            if (!PeekNamedPipe(pipe, IntPtr.Zero, 0, IntPtr.Zero, out available, IntPtr.Zero))
            {
                int error = Marshal.GetLastWin32Error();
                if (error == 109 || error == 232) return true;
                return false;
            }
            if (available == 0) return true;
            long remaining = maximum - sink.Length;
            if (remaining <= 0 || available > (ulong)remaining)
            {
                overflow = true;
                return true;
            }
            uint request = (uint)Math.Min(buffer.Length, (int)available);
            uint read;
            if (!ReadFile(pipe, buffer, request, out read, IntPtr.Zero))
            {
                int error = Marshal.GetLastWin32Error();
                if (error == 109 || error == 232) return true;
                return false;
            }
            if (read == 0) return true;
            sink.Write(buffer, 0, (int)read);
        }
    }

    private static bool RootExited(IntPtr process)
    {
        return WaitForSingleObject(process, 0) == WAIT_OBJECT_0;
    }

    private static bool AwaitTreeExit(IntPtr job, IntPtr process, int milliseconds)
    {
        Stopwatch clock = Stopwatch.StartNew();
        while (clock.ElapsedMilliseconds < milliseconds)
        {
            uint active;
            if (!TryGetActiveProcesses(job, out active)) return false;
            if (active == 0 && RootExited(process)) return true;
            Thread.Sleep(10);
        }
        uint finalActive;
        return TryGetActiveProcesses(job, out finalActive) && finalActive == 0 && RootExited(process);
    }

    private static bool TerminateExactRoot(IntPtr process, int milliseconds, uint exitCode, out bool attempted)
    {
        attempted = false;
        if (process == IntPtr.Zero || process == InvalidHandleValue || milliseconds < 1) return false;
        Stopwatch cleanup = Stopwatch.StartNew();
        for (int attempt = 0; attempt < 2; attempt++)
        {
            if (RootExited(process)) return true;
            attempted = true;
            bool terminateIssued = TerminateProcess(process, exitCode);
            if (!terminateIssued && RootExited(process)) return true;
            int remaining = milliseconds - (int)cleanup.ElapsedMilliseconds;
            if (remaining <= 0) break;
            uint wait = WaitForSingleObject(process, (uint)remaining);
            if (wait == WAIT_OBJECT_0) return true;
            if (wait != WAIT_TIMEOUT) break;
        }
        return RootExited(process);
    }

    public static AwxAutoIntakeJobRunResult Run(string executable, string commandLine, string currentDirectory, int timeoutMilliseconds, int maximumOutputBytes, int cleanupMilliseconds)
    {
        return Run(executable, commandLine, currentDirectory, timeoutMilliseconds, maximumOutputBytes, cleanupMilliseconds, false, false);
    }

    public static AwxAutoIntakeJobRunResult Run(string executable, string commandLine, string currentDirectory, int timeoutMilliseconds, int maximumOutputBytes, int cleanupMilliseconds, bool injectAssignmentFailure, bool probeHandleAllowlist)
    {
        AwxAutoIntakeJobRunResult result = new AwxAutoIntakeJobRunResult();
        IntPtr job = IntPtr.Zero;
        IntPtr stdoutRead = IntPtr.Zero;
        IntPtr stdoutWrite = IntPtr.Zero;
        IntPtr stderrRead = IntPtr.Zero;
        IntPtr stderrWrite = IntPtr.Zero;
        IntPtr stdinHandle = IntPtr.Zero;
        IntPtr sentinelHandle = IntPtr.Zero;
        IntPtr attributeList = IntPtr.Zero;
        IntPtr handleList = IntPtr.Zero;
        bool attributeListInitialized = false;
        bool assignedToJob = false;
        string sentinelPath = null;
        PROCESS_INFORMATION process = new PROCESS_INFORMATION();
        bool processCreated = false;
        MemoryStream stdout = new MemoryStream(Math.Max(0, maximumOutputBytes));
        MemoryStream stderr = new MemoryStream(Math.Max(0, maximumOutputBytes));
        byte[] drainBuffer = new byte[4096];
        try
        {
            if (timeoutMilliseconds < 1 || maximumOutputBytes < 1 || cleanupMilliseconds < 1) { result.Failure = "invalid-bounds"; return result; }
            job = CreateJobObject(IntPtr.Zero, null);
            if (job == IntPtr.Zero || !ConfigureKillOnClose(job)) { result.Failure = "job-create-failed"; return result; }

            SECURITY_ATTRIBUTES attributes = new SECURITY_ATTRIBUTES();
            attributes.nLength = Marshal.SizeOf(typeof(SECURITY_ATTRIBUTES));
            attributes.bInheritHandle = true;
            if (!CreatePipe(out stdoutRead, out stdoutWrite, ref attributes, 4096) || !SetHandleInformation(stdoutRead, HANDLE_FLAG_INHERIT, 0)) { result.Failure = "stdout-pipe-failed"; return result; }
            if (!CreatePipe(out stderrRead, out stderrWrite, ref attributes, 4096) || !SetHandleInformation(stderrRead, HANDLE_FLAG_INHERIT, 0)) { result.Failure = "stderr-pipe-failed"; return result; }
            stdinHandle = CreateFile("NUL", GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE, ref attributes, OPEN_EXISTING, 0, IntPtr.Zero);
            if (stdinHandle == InvalidHandleValue) { result.Failure = "stdin-handle-failed"; return result; }

            if (probeHandleAllowlist)
            {
                sentinelPath = Path.Combine(currentDirectory, "awx-auto-intake-sentinel-" + Guid.NewGuid().ToString("N") + ".tmp");
                File.WriteAllBytes(sentinelPath, new byte[] { 1 });
                sentinelHandle = CreateFile(sentinelPath, GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE, ref attributes, OPEN_EXISTING, 0, IntPtr.Zero);
                if (sentinelHandle == InvalidHandleValue) { result.Failure = "sentinel-create-failed"; return result; }
            }

            IntPtr attributeBytes = IntPtr.Zero;
            bool sizingCall = InitializeProcThreadAttributeList(IntPtr.Zero, 1, 0, ref attributeBytes);
            int sizingError = Marshal.GetLastWin32Error();
            if (sizingCall || sizingError != ERROR_INSUFFICIENT_BUFFER || attributeBytes == IntPtr.Zero) { result.Failure = "handle-list-size-failed"; return result; }
            result.AttributeSizingProbeProven = true;
            attributeList = Marshal.AllocHGlobal(attributeBytes);
            if (!InitializeProcThreadAttributeList(attributeList, 1, 0, ref attributeBytes)) { result.Failure = "handle-list-init-failed"; return result; }
            attributeListInitialized = true;
            handleList = Marshal.AllocHGlobal(IntPtr.Size * 3);
            Marshal.WriteIntPtr(handleList, 0, stdinHandle);
            Marshal.WriteIntPtr(handleList, IntPtr.Size, stdoutWrite);
            Marshal.WriteIntPtr(handleList, IntPtr.Size * 2, stderrWrite);
            if (!UpdateProcThreadAttribute(attributeList, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST, handleList, new IntPtr(IntPtr.Size * 3), IntPtr.Zero, IntPtr.Zero)) { result.Failure = "handle-list-update-failed"; return result; }
            result.HandleAllowlistProven = true;

            STARTUPINFOEX startup = new STARTUPINFOEX();
            startup.StartupInfo.cb = Marshal.SizeOf(typeof(STARTUPINFOEX));
            startup.StartupInfo.dwFlags = (int)STARTF_USESTDHANDLES;
            startup.StartupInfo.hStdInput = stdinHandle;
            startup.StartupInfo.hStdOutput = stdoutWrite;
            startup.StartupInfo.hStdError = stderrWrite;
            startup.lpAttributeList = attributeList;
            StringBuilder mutableCommand = new StringBuilder(commandLine);
            if (!CreateProcess(executable, mutableCommand, IntPtr.Zero, IntPtr.Zero, true, CREATE_SUSPENDED | CREATE_NO_WINDOW | EXTENDED_STARTUPINFO_PRESENT, IntPtr.Zero, currentDirectory, ref startup, out process))
            {
                result.Failure = "process-create-failed";
                return result;
            }
            processCreated = true;
            result.RootProcessId = unchecked((int)process.dwProcessId);
            bool stdoutParentCleared = SetHandleInformation(stdoutWrite, HANDLE_FLAG_INHERIT, 0);
            bool stderrParentCleared = SetHandleInformation(stderrWrite, HANDLE_FLAG_INHERIT, 0);
            bool stdinParentCleared = SetHandleInformation(stdinHandle, HANDLE_FLAG_INHERIT, 0);
            bool sentinelParentCleared = !probeHandleAllowlist || SetHandleInformation(sentinelHandle, HANDLE_FLAG_INHERIT, 0);
            Close(ref stdoutWrite);
            Close(ref stderrWrite);
            Close(ref stdinHandle);
            Close(ref sentinelHandle);

            if (!stdoutParentCleared || !stderrParentCleared || !stdinParentCleared || !sentinelParentCleared)
            {
                result.HandleAllowlistProven = false;
                result.Failure = "parent-handle-inheritance-clear-failed";
                bool attempted;
                result.TreeExited = TerminateExactRoot(process.hProcess, cleanupMilliseconds, 0xE000, out attempted);
                result.RootTerminationAttempted = attempted;
                result.RootTerminationSucceeded = result.TreeExited;
                return result;
            }
            if (probeHandleAllowlist)
            {
                try
                {
                    File.Delete(sentinelPath);
                    if (File.Exists(sentinelPath)) throw new IOException("sentinel-delete-unproven");
                    result.SentinelInherited = false;
                }
                catch
                {
                    result.SentinelInherited = true;
                    result.HandleAllowlistProven = false;
                    result.Failure = "handle-allowlist-unproven";
                    bool attempted;
                    result.TreeExited = TerminateExactRoot(process.hProcess, cleanupMilliseconds, 0xE000, out attempted);
                    result.RootTerminationAttempted = attempted;
                    result.RootTerminationSucceeded = result.TreeExited;
                    return result;
                }
            }

            if (injectAssignmentFailure || !AssignProcessToJobObject(job, process.hProcess))
            {
                result.Failure = injectAssignmentFailure ? "assignment-injected" : "assignment-unproven";
                bool attempted;
                result.TreeExited = TerminateExactRoot(process.hProcess, cleanupMilliseconds, 0xE001, out attempted);
                result.RootTerminationAttempted = attempted;
                result.RootTerminationSucceeded = result.TreeExited;
                return result;
            }
            assignedToJob = true;
            result.AssignmentSucceeded = true;
            if (ResumeThread(process.hThread) == UInt32.MaxValue)
            {
                result.Failure = "resume-failed";
                TerminateJobObject(job, 0xE002);
                result.TreeExited = AwaitTreeExit(job, process.hProcess, cleanupMilliseconds);
                return result;
            }
            result.Resumed = true;

            Stopwatch runtime = Stopwatch.StartNew();
            while (runtime.ElapsedMilliseconds < timeoutMilliseconds)
            {
                bool stdoutOverflow = false;
                bool stderrOverflow = false;
                if (!DrainPipe(stdoutRead, stdout, maximumOutputBytes, drainBuffer, out stdoutOverflow) || !DrainPipe(stderrRead, stderr, maximumOutputBytes, drainBuffer, out stderrOverflow))
                {
                    result.Failure = "output-read-failed";
                    break;
                }
                if (stdoutOverflow || stderrOverflow)
                {
                    result.OutputOverflow = true;
                    break;
                }
                uint active;
                if (!TryGetActiveProcesses(job, out active))
                {
                    result.Failure = "job-query-failed";
                    break;
                }
                if (active == 0 && RootExited(process.hProcess))
                {
                    result.TreeExited = true;
                    break;
                }
                Thread.Sleep(10);
            }

            if (!result.TreeExited)
            {
                if (result.Failure.Length == 0 && !result.OutputOverflow) result.TimedOut = true;
                if (!TerminateJobObject(job, 0xE003) && result.Failure.Length == 0) result.Failure = "job-terminate-failed";
                result.TreeExited = AwaitTreeExit(job, process.hProcess, cleanupMilliseconds);
            }

            if (result.TreeExited && !result.OutputOverflow)
            {
                bool stdoutOverflow = false;
                bool stderrOverflow = false;
                if (!DrainPipe(stdoutRead, stdout, maximumOutputBytes, drainBuffer, out stdoutOverflow) || !DrainPipe(stderrRead, stderr, maximumOutputBytes, drainBuffer, out stderrOverflow)) result.Failure = "output-read-failed";
                if (stdoutOverflow || stderrOverflow) result.OutputOverflow = true;
            }

            uint exitCode;
            if (RootExited(process.hProcess) && GetExitCodeProcess(process.hProcess, out exitCode) && exitCode != STILL_ACTIVE) result.ExitCode = unchecked((int)exitCode);
            return result;
        }
        finally
        {
            result.Stdout = stdout.ToArray();
            result.Stderr = stderr.ToArray();
            if (processCreated && !result.TreeExited)
            {
                if (assignedToJob)
                {
                    if (job != IntPtr.Zero) TerminateJobObject(job, 0xE004);
                    result.TreeExited = job != IntPtr.Zero && AwaitTreeExit(job, process.hProcess, Math.Max(1, cleanupMilliseconds));
                }
                else
                {
                    bool attempted;
                    result.TreeExited = TerminateExactRoot(process.hProcess, Math.Max(1, cleanupMilliseconds), 0xE004, out attempted);
                    result.RootTerminationAttempted = result.RootTerminationAttempted || attempted;
                    result.RootTerminationSucceeded = result.TreeExited;
                }
            }
            Close(ref process.hThread);
            Close(ref process.hProcess);
            Close(ref stdoutRead);
            Close(ref stdoutWrite);
            Close(ref stderrRead);
            Close(ref stderrWrite);
            Close(ref stdinHandle);
            Close(ref sentinelHandle);
            if (attributeListInitialized) DeleteProcThreadAttributeList(attributeList);
            if (attributeList != IntPtr.Zero) Marshal.FreeHGlobal(attributeList);
            if (handleList != IntPtr.Zero) Marshal.FreeHGlobal(handleList);
            Close(ref job);
            if (!String.IsNullOrEmpty(sentinelPath)) { try { File.Delete(sentinelPath); } catch { } }
            stdout.Dispose();
            stderr.Dispose();
        }
    }
}
'@ -Language CSharp -ErrorAction Stop
}

function New-AwxAutoIntakeDecision {
    param(
        [string]$Decision,
        [string]$FailureClass,
        [string]$Mode = '',
        [string]$PatchName = '',
        [string]$BundleIdHash = '',
        [int]$QueueCount = 0
    )
    [pscustomobject]@{
        decision = $Decision
        failureClass = $FailureClass
        mode = $Mode
        patchName = $PatchName
        bundleIdHash = $BundleIdHash
        queueCount = $QueueCount
        validatorVersion = 'awx.desktop-patchdrop-auto-intake.v1'
    }
}

function Read-AwxAutoIntakeBoundedStreamBytes {
    param(
        [Parameter(Mandatory)][System.IO.Stream]$Stream,
        [Parameter(Mandatory)][int64]$MaxBytes,
        [Parameter(Mandatory)][string]$FailureClass
    )
    try {
        if (-not $Stream.CanRead -or -not $Stream.CanSeek -or $MaxBytes -lt 1 -or $Stream.Length -lt 0 -or $Stream.Length -gt $MaxBytes) { throw 'invalid' }
        $Stream.Position = 0
        $memory = [System.IO.MemoryStream]::new([int][Math]::Min([int64][int]::MaxValue, $Stream.Length))
        try {
            $buffer = [byte[]]::new(8192)
            $total = [int64]0
            while (($read = $Stream.Read($buffer, 0, $buffer.Length)) -gt 0) {
                $total += $read
                if ($total -gt $MaxBytes) { throw 'invalid' }
                $memory.Write($buffer, 0, $read)
            }
            if ($total -ne $Stream.Length) { throw 'invalid' }
            return $memory.ToArray()
        } finally { $memory.Dispose() }
    } catch {
        throw [System.InvalidOperationException]::new($FailureClass)
    }
}

function Read-AwxAutoIntakeBoundedFileBytes {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][int64]$MaxBytes,
        [Parameter(Mandatory)][string]$FailureClass
    )
    $stream = $null
    try {
        $item = Get-Item -LiteralPath $Path -Force -ErrorAction Stop
        if ($item.PSIsContainer -or $item.Length -lt 0 -or $item.Length -gt $MaxBytes) { throw [System.InvalidOperationException]::new($FailureClass) }
        $stream = [System.IO.File]::Open($item.FullName, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::Read)
        return Read-AwxAutoIntakeBoundedStreamBytes -Stream $stream -MaxBytes $MaxBytes -FailureClass $FailureClass
    } catch [System.InvalidOperationException] { throw }
    catch { throw [System.InvalidOperationException]::new($FailureClass) }
    finally { if ($null -ne $stream) { $stream.Dispose() } }
}

function Read-AwxAutoIntakeBundleManifestSnapshot {
    param(
        [Parameter(Mandatory)][string]$Path,
        [scriptblock]$AfterRead = $null
    )
    $stream = $null
    try {
        $fullPath = [System.IO.Path]::GetFullPath($Path)
        $stream = [System.IO.File]::Open($fullPath, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::Read)
        $bytes = Read-AwxAutoIntakeBoundedStreamBytes -Stream $stream -MaxBytes $script:AwxAutoIntakeMaxManifestBytes -FailureClass 'manifest-snapshot-changed'
        if ($null -ne $AfterRead) { & $AfterRead }
        $text = [System.Text.UTF8Encoding]::new($false, $true).GetString($bytes)
        return [pscustomobject][ordered]@{ bytes = $bytes; text = $text; sha256 = Get-AwxAutoIntakeSha256 $bytes }
    } catch {
        throw [System.InvalidOperationException]::new('manifest-snapshot-changed')
    } finally { if ($null -ne $stream) { $stream.Dispose() } }
}

function Read-AwxAutoIntakeUtf8File {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][int]$MaxBytes)
    try {
        $bytes = Read-AwxAutoIntakeBoundedFileBytes -Path $Path -MaxBytes $MaxBytes -FailureClass 'policy-invalid'
        return [System.Text.UTF8Encoding]::new($false, $true).GetString($bytes)
    } catch {
        throw [System.InvalidOperationException]::new('policy-invalid')
    }
}

function Test-AwxAutoIntakeAsciiEnum {
    param([object]$Value, [string[]]$Allowed)
    return ($Value -is [string]) -and $Value -cmatch '^[\x20-\x7E]+$' -and ($Allowed -ccontains $Value)
}

function Test-AwxAutoIntakeStringArray {
    param([object]$Value, [switch]$PathPrefix)
    if ($null -eq $Value -or $Value -is [string] -or -not ($Value -is [System.Collections.IEnumerable])) { return $false }
    $values = @($Value)
    if ($values.Count -gt $script:AwxAutoIntakeMaxAllowlistEntries) { return $false }
    $seen = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    foreach ($entry in $values) {
        if ($entry -isnot [string] -or $entry.Length -eq 0 -or $entry.Length -gt 160 -or $entry -cmatch '[^\x20-\x7E]') { return $false }
        if (-not $seen.Add($entry)) { return $false }
        if ($PathPrefix -and ($entry -match '(^[A-Za-z]:[\\/]|^[\\/]{1,2}|(^|[\\/])\.\.([\\/]|$))')) { return $false }
    }
    return $true
}

function Test-AwxAutoIntakeSafeValue {
    param([object]$Value)
    if ($Value -is [string]) {
        return $Value -notmatch '(?i)(sk-[a-z0-9]|AIza[0-9A-Za-z_-]|bearer\s+|-----BEGIN|password\s*=|token\s*=|secret\s*=)'
    }
    if ($Value -is [System.Collections.IEnumerable] -and $Value -isnot [string]) {
        foreach ($entry in @($Value)) { if (-not (Test-AwxAutoIntakeSafeValue $entry)) { return $false } }
    }
    return $true
}

function ConvertFrom-AwxAutoIntakePolicyBytes {
    [CmdletBinding()]
    param([Parameter(Mandatory)][AllowEmptyCollection()][byte[]]$Bytes)
    try {
        if ($Bytes.Length -lt 1 -or $Bytes.Length -gt $script:AwxAutoIntakeMaxPolicyBytes) { throw 'invalid' }
        $content = [System.Text.UTF8Encoding]::new($false, $true).GetString($Bytes)
        $policy = $content | ConvertFrom-Json -ErrorAction Stop
        if ($null -eq $policy -or $policy -isnot [pscustomobject]) { throw 'invalid' }
        $names = @($policy.PSObject.Properties | ForEach-Object Name)
        if ($names.Count -ne $script:AwxAutoIntakeRequiredFields.Count) { throw 'invalid' }
        foreach ($required in $script:AwxAutoIntakeRequiredFields) { if ($names -notcontains $required) { throw 'invalid' } }
        if ([string]$policy.schemaVersion -cne $script:AwxAutoIntakePolicySchema -or $policy.enabled -isnot [bool]) { throw 'invalid' }
        if (-not (Test-AwxAutoIntakeAsciiEnum $policy.mode @('OBSERVE', 'APPLY'))) { throw 'invalid' }
        if (-not (Test-AwxAutoIntakeAsciiEnum $policy.verificationProfile @('focused'))) { throw 'invalid' }
        foreach ($hashName in @('expectedBackingShareIdentitySha256', 'expectedShareAclSha256')) {
            $value = $policy.$hashName
            if ($value -isnot [string] -or ($value.Length -gt 0 -and $value -cnotmatch '^[A-F0-9]{64}$')) { throw 'invalid' }
        }
        foreach ($arrayName in @('allowedNodes', 'allowedTopics')) {
            if (-not (Test-AwxAutoIntakeStringArray $policy.$arrayName)) { throw 'invalid' }
        }
        if (-not (Test-AwxAutoIntakeStringArray $policy.allowedPathPrefixes -PathPrefix)) { throw 'invalid' }
        foreach ($numberName in @('maxPatchBytes', 'maxChangedFiles', 'maxHunks', 'pollIntervalMinutes')) {
            $value = $policy.$numberName
            if ($value -isnot [long] -and $value -isnot [int]) { throw 'invalid' }
            if ($value -lt 0) { throw 'invalid' }
        }
        if ($policy.maxPatchBytes -lt 1 -or $policy.maxChangedFiles -lt 1 -or $policy.maxHunks -lt 1 -or $policy.pollIntervalMinutes -lt 1 -or $policy.pollIntervalMinutes -gt 60) { throw 'invalid' }
        if ($policy.mode -ceq 'APPLY' -and ((@($policy.allowedNodes).Count -eq 0) -or (@($policy.allowedTopics).Count -eq 0) -or (@($policy.allowedPathPrefixes).Count -eq 0))) { throw 'invalid' }
        foreach ($property in $policy.PSObject.Properties) { if (-not (Test-AwxAutoIntakeSafeValue $property.Value)) { throw 'invalid' } }
        return $policy
    } catch {
        throw [System.InvalidOperationException]::new('policy-invalid')
    }
}

function Read-AwxAutoIntakePolicy {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Path)
    try {
        $bytes = Read-AwxAutoIntakeBoundedFileBytes -Path $Path -MaxBytes $script:AwxAutoIntakeMaxPolicyBytes -FailureClass 'policy-invalid'
        return ConvertFrom-AwxAutoIntakePolicyBytes -Bytes $bytes
    } catch {
        throw [System.InvalidOperationException]::new('policy-invalid')
    }
}

function Get-AwxAutoIntakeSha256 {
    param([Parameter(Mandatory)][AllowEmptyCollection()][byte[]]$Bytes)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try { return ([System.BitConverter]::ToString($sha.ComputeHash($Bytes))).Replace('-', '').ToLowerInvariant() }
    finally { $sha.Dispose() }
}

function Get-AwxAutoIntakeFileSha256 {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][int64]$MaxBytes)
    $stream = $null
    try {
        $item = Get-Item -LiteralPath $Path -Force -ErrorAction Stop
        if ($item.PSIsContainer -or $item.Length -lt 1 -or $item.Length -gt $MaxBytes) { throw 'invalid' }
        $stream = [System.IO.FileStream]::new($item.FullName, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::Read)
        $sha = [System.Security.Cryptography.SHA256]::Create()
        try { return ([System.BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-', '').ToLowerInvariant() }
        finally { $sha.Dispose() }
    } catch {
        throw [System.InvalidOperationException]::new('bundle-identity-unproven')
    } finally {
        if ($null -ne $stream) { $stream.Dispose() }
    }
}

function Get-AwxAutoIntakePathSha256 {
    param([Parameter(Mandatory)][string]$Path)
    $normalized = [System.IO.Path]::GetFullPath($Path).TrimEnd('\')
    return Get-AwxAutoIntakeSha256 ([System.Text.UTF8Encoding]::new($false).GetBytes($normalized))
}

function Get-AwxAutoIntakeBundleIdentity {
    param([Parameter(Mandatory)][string]$Root, [Parameter(Mandatory)][string]$PatchName, [switch]$TestMode)
    try {
        if (-not (Test-AwxAutoIntakeBarePatchName $PatchName)) { throw 'invalid' }
        $rootPath = [System.IO.Path]::GetFullPath($Root).TrimEnd('\')
        $bundleRoot = if ($TestMode) { $rootPath } else { Join-Path $rootPath '__patch_drop__' }
        $patchPath = Join-Path $bundleRoot $PatchName
        $base = [System.IO.Path]::GetFileNameWithoutExtension($PatchName)
        $manifestPath = Join-Path $bundleRoot ($base + '.manifest.json')
        if (-not (Test-AwxAutoIntakePathContained -Root $bundleRoot -Path $patchPath) -or -not (Test-AwxAutoIntakePathContained -Root $bundleRoot -Path $manifestPath)) { throw 'invalid' }
        $patchSha = Get-AwxAutoIntakeFileSha256 -Path $patchPath -MaxBytes $script:AwxAutoIntakeMaxAdapterPatchBytes
        $manifestSha = Get-AwxAutoIntakeFileSha256 -Path $manifestPath -MaxBytes $script:AwxAutoIntakeMaxManifestBytes
        $bundleId = Get-AwxAutoIntakeSha256 ([System.Text.UTF8Encoding]::new($false).GetBytes($PatchName))
        $rootSha = Get-AwxAutoIntakePathSha256 $bundleRoot
        $replay = Get-AwxAutoIntakeSha256 ([System.Text.UTF8Encoding]::new($false).GetBytes($rootSha + ':' + $bundleId + ':' + $patchSha + ':' + $manifestSha))
        return [pscustomobject]@{
            rootPathSha256 = $rootSha
            patchName = $PatchName
            bundleIdHash = $bundleId
            patchSha256 = $patchSha
            manifestSha256 = $manifestSha
            replayHash = $replay
        }
    } catch {
        throw [System.InvalidOperationException]::new('bundle-identity-unproven')
    }
}

function Test-AwxAutoIntakeSnapshotIdentity {
    param([object]$Snapshot, [object]$Identity)
    if ($null -eq $Snapshot -or $null -eq $Snapshot.candidate -or $null -eq $Identity) { return $false }
    try {
        $candidate = $Snapshot.candidate
        foreach ($name in @('rootPathSha256','patchName','bundleIdHash','patchSha256','manifestSha256')) {
            if ($candidate.PSObject.Properties[$name] -eq $null -or $Identity.PSObject.Properties[$name] -eq $null -or [string]$candidate.$name -cne [string]$Identity.$name) { return $false }
        }
        $expectedReplay = Get-AwxAutoIntakeReplayHash -Snapshot $Snapshot
        return [string]$Identity.replayHash -ceq $expectedReplay
    } catch { return $false }
}

function Open-AwxAutoIntakeBundleSnapshotGuard {
    param(
        [Parameter(Mandatory)][string]$Root,
        [Parameter(Mandatory)][string]$PatchName,
        [Parameter(Mandatory)][object]$ExpectedSnapshot,
        [switch]$TestMode
    )
    $patchHandle = $null
    $manifestHandle = $null
    try {
        if (-not (Test-AwxAutoIntakeBarePatchName $PatchName)) { throw 'invalid' }
        $rootPath = [System.IO.Path]::GetFullPath($Root).TrimEnd('\')
        $bundleRoot = if ($TestMode) { $rootPath } else { Join-Path $rootPath '__patch_drop__' }
        $patchPath = Join-Path $bundleRoot $PatchName
        $base = [System.IO.Path]::GetFileNameWithoutExtension($PatchName)
        $manifestPath = Join-Path $bundleRoot ($base + '.manifest.json')
        if (-not (Test-AwxAutoIntakePathContained -Root $bundleRoot -Path $patchPath) -or -not (Test-AwxAutoIntakePathContained -Root $bundleRoot -Path $manifestPath)) { throw 'invalid' }
        if (-not (Test-AwxAutoIntakeReparseFreePath -Root $bundleRoot -AdapterPath $patchPath) -or -not (Test-AwxAutoIntakeReparseFreePath -Root $bundleRoot -AdapterPath $manifestPath)) { throw 'invalid' }
        $patchHandle = [System.IO.File]::Open($patchPath, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::Read)
        $manifestHandle = [System.IO.File]::Open($manifestPath, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::Read)
        $patchBytes = Read-AwxAutoIntakeBoundedStreamBytes -Stream $patchHandle -MaxBytes $script:AwxAutoIntakeMaxAdapterPatchBytes -FailureClass 'bundle-snapshot-changed'
        $manifestBytes = Read-AwxAutoIntakeBoundedStreamBytes -Stream $manifestHandle -MaxBytes $script:AwxAutoIntakeMaxManifestBytes -FailureClass 'bundle-snapshot-changed'
        $rootSha = Get-AwxAutoIntakePathSha256 $bundleRoot
        $bundleId = Get-AwxAutoIntakeSha256 ([System.Text.UTF8Encoding]::new($false).GetBytes($PatchName))
        $patchSha = Get-AwxAutoIntakeSha256 $patchBytes
        $manifestSha = Get-AwxAutoIntakeSha256 $manifestBytes
        $replayBytes = [System.Text.UTF8Encoding]::new($false).GetBytes($rootSha + ':' + $bundleId + ':' + $patchSha + ':' + $manifestSha)
        $identity = [pscustomobject]@{
            rootPathSha256 = $rootSha
            patchName = $PatchName
            bundleIdHash = $bundleId
            patchSha256 = $patchSha
            manifestSha256 = $manifestSha
            replayHash = Get-AwxAutoIntakeSha256 $replayBytes
        }
        if (-not (Test-AwxAutoIntakeSnapshotIdentity -Snapshot $ExpectedSnapshot -Identity $identity)) { throw 'changed' }
        $guard = [pscustomobject]@{ PatchHandle = $patchHandle; ManifestHandle = $manifestHandle; Identity = $identity }
        $guard | Add-Member -MemberType ScriptMethod -Name Dispose -Value {
            if ($null -ne $this.ManifestHandle) { $this.ManifestHandle.Dispose(); $this.ManifestHandle = $null }
            if ($null -ne $this.PatchHandle) { $this.PatchHandle.Dispose(); $this.PatchHandle = $null }
        }
        $patchHandle = $null
        $manifestHandle = $null
        return $guard
    } catch {
        throw [System.InvalidOperationException]::new('bundle-snapshot-changed')
    } finally {
        if ($null -ne $manifestHandle) { $manifestHandle.Dispose() }
        if ($null -ne $patchHandle) { $patchHandle.Dispose() }
    }
}

function Test-AwxAutoIntakeExactPropertySet {
    param([object]$Value, [string[]]$Expected)
    if ($null -eq $Value) { return $false }
    $names = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    foreach ($property in $Value.PSObject.Properties) { if (-not $names.Add([string]$property.Name)) { return $false } }
    if ($names.Count -ne $Expected.Count) { return $false }
    foreach ($name in $Expected) { if (-not $names.Contains($name)) { return $false } }
    return $true
}

function Test-AwxAutoIntakeNonNegativeCount {
    param([object]$Value)
    return (($Value -is [int] -or $Value -is [long]) -and $Value -ge 0)
}

function Test-AwxAutoIntakeRelativePath {
    param([object]$Value)
    return (
        $Value -is [string] -and
        $Value.Length -gt 0 -and
        $Value.Length -le 320 -and
        $Value -cmatch '^[\x20-\x7E]+$' -and
        $Value -cnotmatch '(^[A-Za-z]:[\\/]|^[\\/]{1,2}|(^|[\\/])\.\.([\\/]|$))'
    )
}

function Test-AwxAutoIntakeAllowedPath {
    param([string]$Path, [string[]]$Prefixes)
    if (-not (Test-AwxAutoIntakeRelativePath $Path)) { return $false }
    $normalizedPath = $Path.Replace('\', '/').Trim('/')
    foreach ($prefix in $Prefixes) {
        if (-not (Test-AwxAutoIntakeRelativePath $prefix)) { return $false }
        $normalizedPrefix = $prefix.Replace('\', '/').Trim('/')
        if ($normalizedPath -ceq $normalizedPrefix -or $normalizedPath.StartsWith($normalizedPrefix + '/', [System.StringComparison]::Ordinal)) { return $true }
    }
    return $false
}

function Test-AwxAutoIntakeApplyEvidence {
    [CmdletBinding()]
    param([object]$Policy, [object]$Snapshot, [object]$Evidence)
    if ($null -eq $Policy -or $null -eq $Snapshot -or $null -eq $Snapshot.candidate) {
        return New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'apply-evidence-invalid'
    }
    $secretProperties = @($Evidence.PSObject.Properties | Where-Object { $_.Name -ceq 'secretPatternHitCount' })
    if ($secretProperties.Count -eq 1 -and (Test-AwxAutoIntakeNonNegativeCount $secretProperties[0].Value) -and [int64]$secretProperties[0].Value -gt 0) { return New-AwxAutoIntakeDecision -Decision 'REJECT' -FailureClass 'secret-leak-risk' }
    if (-not (Test-AwxAutoIntakeExactPropertySet -Value $Evidence -Expected $script:AwxAutoIntakeApplyEvidenceFields)) {
        return New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'apply-evidence-invalid'
    }
    foreach ($name in @('canonicalRootProven','backingShareIdentityVerified','shareAclHashMatch','indexLock','preimageMatch','sourceIsolationPass','rollbackReady')) {
        if ($Evidence.$name -isnot [bool]) { return New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'apply-evidence-invalid' }
    }
    foreach ($name in @('activeLeaseCount','dirtyOverlapCount','secretPatternHitCount','patchBytes','changedFileCount','hunkCount')) {
        if (-not (Test-AwxAutoIntakeNonNegativeCount $Evidence.$name)) { return New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'apply-evidence-invalid' }
    }
    if ([int64]$Evidence.secretPatternHitCount -gt 0) { return New-AwxAutoIntakeDecision -Decision 'REJECT' -FailureClass 'secret-leak-risk' }
    if (-not [bool]$Evidence.canonicalRootProven) { return New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'canonical-root-unproven' }
    if (-not [bool]$Evidence.backingShareIdentityVerified) { return New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'backing-share-identity-unproven' }
    if (-not [bool]$Evidence.shareAclHashMatch) { return New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'share-acl-unproven' }
    if ([bool]$Evidence.indexLock) { return New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'index-lock-present' }
    if ([int64]$Evidence.activeLeaseCount -gt 0) { return New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'source-lease-conflict' }
    if ([int64]$Evidence.dirtyOverlapCount -gt 0) { return New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'dirty-overlap' }
    if (-not [bool]$Evidence.preimageMatch) { return New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'changed-preimage' }
    if (-not [bool]$Evidence.sourceIsolationPass) { return New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'source-isolation-unproven' }
    if ([int64]$Evidence.patchBytes -gt [int64]$Policy.maxPatchBytes) { return New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'patch-budget-exceeded' }
    if ([int64]$Evidence.changedFileCount -gt [int64]$Policy.maxChangedFiles) { return New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'changed-file-budget-exceeded' }
    if ([int64]$Evidence.hunkCount -gt [int64]$Policy.maxHunks) { return New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'hunk-budget-exceeded' }
    if (-not [bool]$Evidence.rollbackReady) { return New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'rollback-unproven' }
    $candidate = $Snapshot.candidate
    foreach ($metric in @('patchBytes','changedFileCount','hunkCount')) {
        if ($candidate.PSObject.Properties[$metric] -eq $null -or -not (Test-AwxAutoIntakeNonNegativeCount $candidate.$metric) -or [int64]$candidate.$metric -lt 1 -or [int64]$Evidence.$metric -ne [int64]$candidate.$metric) { return New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'snapshot-metrics-mismatch' }
    }
    if ($candidate.PSObject.Properties['node'] -eq $null -or [string]::IsNullOrEmpty([string]$candidate.node)) { return New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'manifest-node-unproven' }
    if ($candidate.PSObject.Properties['topic'] -eq $null -or [string]::IsNullOrEmpty([string]$candidate.topic)) { return New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'manifest-topic-unproven' }
    if (@($Policy.allowedNodes) -cnotcontains [string]$candidate.node) { return New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'manifest-node-not-allowed' }
    if (@($Policy.allowedTopics) -cnotcontains [string]$candidate.topic) { return New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'manifest-topic-not-allowed' }
    if ($candidate.PSObject.Properties['changedPaths'] -eq $null -or @($candidate.changedPaths).Count -eq 0) { return New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'manifest-paths-unproven' }
    foreach ($path in @($candidate.changedPaths)) {
        if (-not (Test-AwxAutoIntakeAllowedPath -Path ([string]$path) -Prefixes @($Policy.allowedPathPrefixes))) { return New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'manifest-path-not-allowed' }
    }
    return New-AwxAutoIntakeDecision -Decision 'APPLY'
}

function Get-AwxAutoIntakePatchSummary {
    param([Parameter(Mandatory)][byte[]]$Bytes)
    try { $text = [System.Text.UTF8Encoding]::new($false, $true).GetString($Bytes) } catch { return [pscustomobject]@{ valid = $false; paths = @(); patchBytes = 0; changedFileCount = 0; hunkCount = 0 } }
    $headers = @([regex]::Matches($text, '(?m)^diff --git.*$'))
    if ($headers.Count -eq 0) { return [pscustomobject]@{ valid = $false; paths = @(); patchBytes = $Bytes.Length; changedFileCount = 0; hunkCount = 0 } }
    $paths = [System.Collections.Generic.List[string]]::new()
    $seen = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    foreach ($header in $headers) {
        $match = [regex]::Match($header.Value, '^diff --git a/([^\r\n\s]+) b/([^\r\n\s]+)$')
        if (-not $match.Success) { return [pscustomobject]@{ valid = $false; paths = @(); patchBytes = $Bytes.Length; changedFileCount = 0; hunkCount = 0 } }
        $left = $match.Groups[1].Value
        $right = $match.Groups[2].Value
        if ($left -cne $right -or -not (Test-AwxAutoIntakeRelativePath $left) -or -not $seen.Add($left)) { return [pscustomobject]@{ valid = $false; paths = @(); patchBytes = $Bytes.Length; changedFileCount = 0; hunkCount = 0 } }
        $paths.Add($left) | Out-Null
    }
    return [pscustomobject]@{ valid = $true; paths = @($paths); patchBytes = $Bytes.Length; changedFileCount = $paths.Count; hunkCount = @([regex]::Matches($text, '(?m)^@@ ')).Count }
}

function Get-AwxTopLevelBundleSnapshot {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Root)
    $empty = [pscustomobject]@{ queueCount = 0; failureClass = 'queue-empty'; candidate = $null; candidates = @() }
    try {
        if (-not (Test-Path -LiteralPath $Root -PathType Container)) {
            return [pscustomobject]@{ queueCount = 0; failureClass = 'patch-drop-root-unproven'; candidate = $null; candidates = @() }
        }
        $v3Patches = @(Get-ChildItem -LiteralPath $Root -File -Filter '*-v3.patch' -ErrorAction Stop)
        $allPatches = @(Get-ChildItem -LiteralPath $Root -File -Filter '*.patch' -ErrorAction Stop)
        if ($allPatches.Count -eq 0) { return $empty }
        if ($allPatches.Count -ne 1 -or $v3Patches.Count -ne 1) {
            $failure = if ($allPatches.Count -gt 1) { 'patch-drop-pending' } else { 'manifest-invalid' }
            return [pscustomobject]@{ queueCount = $allPatches.Count; failureClass = $failure; candidate = $null; candidates = @() }
        }
        $patch = $v3Patches[0]
        if ($patch.Name -cnotmatch '^[A-Za-z0-9][A-Za-z0-9._-]*-v3\.patch$') {
            return [pscustomobject]@{ queueCount = 1; failureClass = 'manifest-invalid'; candidate = $null; candidates = @() }
        }
        $base = [System.IO.Path]::GetFileNameWithoutExtension($patch.Name)
        foreach ($suffix in @('.report.md', '.verify.log', '.sha256.txt', '.manifest.json')) {
            if (-not (Test-Path -LiteralPath (Join-Path $Root ($base + $suffix)) -PathType Leaf)) {
                return [pscustomobject]@{ queueCount = 1; failureClass = 'missing-bundle-meta'; candidate = $null; candidates = @() }
            }
        }
        try {
            $manifestPath = Join-Path $Root ($base + '.manifest.json')
            $manifestSnapshot = Read-AwxAutoIntakeBundleManifestSnapshot -Path $manifestPath
            $manifest = $manifestSnapshot.text | ConvertFrom-Json -ErrorAction Stop
            if ($null -eq $manifest -or $manifest.activePatch -isnot [string] -or $manifest.activePatch -cne $patch.Name) { throw 'invalid' }
        } catch {
            return [pscustomobject]@{ queueCount = 1; failureClass = 'manifest-invalid'; candidate = $null; candidates = @() }
        }
        try {
            $patchBytes = Read-AwxAutoIntakeBoundedFileBytes -Path $patch.FullName -MaxBytes $script:AwxAutoIntakeMaxAdapterPatchBytes -FailureClass 'patch-size-limit-exceeded'
        } catch [System.InvalidOperationException] {
            if ($_.Exception.Message -ceq 'patch-size-limit-exceeded') {
                return [pscustomobject]@{ queueCount = 1; failureClass = 'patch-size-limit-exceeded'; candidate = $null; candidates = @() }
            }
            throw
        }
        $patchSummary = Get-AwxAutoIntakePatchSummary $patchBytes
        if (-not [bool]$patchSummary.valid) {
            return [pscustomobject]@{ queueCount = 1; failureClass = 'patch-headers-invalid'; candidate = $null; candidates = @() }
        }
        $candidate = [pscustomobject]@{
            patchName = $patch.Name
            bundleIdHash = Get-AwxAutoIntakeSha256 ([System.Text.UTF8Encoding]::new($false).GetBytes($patch.Name))
            patchSha256 = Get-AwxAutoIntakeSha256 $patchBytes
            manifestSha256 = [string]$manifestSnapshot.sha256
            rootPathSha256 = Get-AwxAutoIntakePathSha256 $Root
            node = if ($null -ne $manifest.PSObject.Properties['node']) { [string]$manifest.node } else { '' }
            topic = if ($null -ne $manifest.PSObject.Properties['topic']) { [string]$manifest.topic } else { '' }
            changedPaths = @($patchSummary.paths)
            patchBytes = [int64]$patchSummary.patchBytes
            changedFileCount = [int64]$patchSummary.changedFileCount
            hunkCount = [int64]$patchSummary.hunkCount
        }
        return [pscustomobject]@{ queueCount = 1; failureClass = ''; candidate = $candidate; candidates = @($candidate) }
    } catch {
        return [pscustomobject]@{ queueCount = 0; failureClass = 'queue-scan-failed'; candidate = $null; candidates = @() }
    }
}

function Get-AwxAutoIntakeDecision {
    [CmdletBinding()]
    param([object]$Policy, [object]$Snapshot, [object]$Evidence)
    if ($null -eq $Policy -or ($null -ne $Evidence -and $Evidence.PSObject.Properties['policyValid'] -and -not [bool]$Evidence.policyValid)) {
        return New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'policy-invalid'
    }
    $mode = [string]$Policy.mode
    if ($Policy.enabled -isnot [bool] -or $mode -cnotin @('OBSERVE', 'APPLY')) { return New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'policy-invalid' }
    $queueCount = if ($null -eq $Snapshot) { 0 } else { [int]$Snapshot.queueCount }
    if (-not [bool]$Policy.enabled) { return New-AwxAutoIntakeDecision -Decision 'NOOP' -FailureClass 'policy-disabled' -Mode $mode -QueueCount $queueCount }
    if ($null -eq $Snapshot) { return New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'queue-scan-failed' -Mode $mode }
    $failure = [string]$Snapshot.failureClass
    if ($failure -eq 'queue-empty') { return New-AwxAutoIntakeDecision -Decision 'NOOP' -FailureClass 'queue-empty' -Mode $mode -QueueCount $queueCount }
    if ($failure.Length -gt 0) { return New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass $failure -Mode $mode -QueueCount $queueCount }
    if ($null -eq $Snapshot.candidate) { return New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'manifest-invalid' -Mode $mode -QueueCount $queueCount }
    $patchName = [string]$Snapshot.candidate.patchName
    $bundleIdHash = [string]$Snapshot.candidate.bundleIdHash
    if ($mode -ceq 'OBSERVE') { return New-AwxAutoIntakeDecision -Decision 'OBSERVE' -Mode $mode -PatchName $patchName -BundleIdHash $bundleIdHash -QueueCount $queueCount }
    if (-not (Test-AwxAutoIntakeExactPropertySet -Value $Evidence -Expected $script:AwxAutoIntakeApplyEvidenceFields)) {
        return New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'apply-preconditions-unproven' -Mode $mode -PatchName $patchName -BundleIdHash $bundleIdHash -QueueCount $queueCount
    }
    $apply = Test-AwxAutoIntakeApplyEvidence -Policy $Policy -Snapshot $Snapshot -Evidence $Evidence
    return New-AwxAutoIntakeDecision -Decision $apply.decision -FailureClass $apply.failureClass -Mode $mode -PatchName $patchName -BundleIdHash $bundleIdHash -QueueCount $queueCount
}

function Get-AwxAutoIntakeStateRoot {
    param([Parameter(Mandatory)][string]$StateRoot)
    try {
        $full = [System.IO.Path]::GetFullPath($StateRoot)
        if (Test-Path -LiteralPath $full -PathType Leaf) { throw 'invalid' }
        if (-not (Test-Path -LiteralPath $full -PathType Container)) { New-Item -ItemType Directory -Path $full -Force -ErrorAction Stop | Out-Null }
        return $full
    } catch {
        throw [System.InvalidOperationException]::new('state-root-invalid')
    }
}

function Write-AwxAutoIntakeAtomicUtf8 {
    param([Parameter(Mandatory)][string]$Directory, [Parameter(Mandatory)][string]$Name, [Parameter(Mandatory)][string]$Content)
    $target = Join-Path $Directory $Name
    $temp = Join-Path $Directory ('.' + $Name + '.' + [guid]::NewGuid().ToString('N') + '.tmp')
    $bytes = [System.Text.UTF8Encoding]::new($false).GetBytes($Content)
    $replacementComplete = $false
    try {
        $stream = [System.IO.FileStream]::new($temp, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
        try { $stream.Write($bytes, 0, $bytes.Length); $stream.Flush($true) } finally { $stream.Dispose() }
        if ((Get-AwxAutoIntakeSha256 ([System.IO.File]::ReadAllBytes($temp))) -cne (Get-AwxAutoIntakeSha256 $bytes)) { throw 'temp-hash-mismatch' }
        if (Test-Path -LiteralPath $target -PathType Leaf) {
            $backup = Join-Path $Directory ('.' + $Name + '.' + [guid]::NewGuid().ToString('N') + '.bak')
            [System.IO.File]::Replace($temp, $target, $backup, $true)
            $replacementComplete = $true
            if ([bool]$script:AwxAutoIntakeContractInjectBackupCleanupFailure) { throw [System.InvalidOperationException]::new('state-replaced-backup-cleanup-unproven') }
            if (Test-Path -LiteralPath $backup -PathType Leaf) { Remove-Item -LiteralPath $backup -Force -ErrorAction Stop }
        }
        else { [System.IO.File]::Move($temp, $target) }
    } catch [System.InvalidOperationException] {
        if ($replacementComplete) { throw [System.InvalidOperationException]::new('state-replaced-backup-cleanup-unproven') }
        if (Test-Path -LiteralPath $temp -PathType Leaf) { Remove-Item -LiteralPath $temp -Force -ErrorAction SilentlyContinue }
        throw [System.InvalidOperationException]::new('state-write-failed')
    } catch {
        if (Test-Path -LiteralPath $temp -PathType Leaf) { Remove-Item -LiteralPath $temp -Force -ErrorAction SilentlyContinue }
        if ($replacementComplete) { throw [System.InvalidOperationException]::new('state-replaced-backup-cleanup-unproven') }
        throw [System.InvalidOperationException]::new('state-write-failed')
    }
}

function Enter-AwxAutoIntakeLock {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$StateRoot)
    $root = Get-AwxAutoIntakeStateRoot $StateRoot
    $path = Join-Path $root 'auto-intake.lock.json'
    $token = [guid]::NewGuid().ToString('N')
    try {
        $stream = [System.IO.FileStream]::new($path, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
    } catch [System.IO.IOException] {
        try {
            try {
                $probe = [System.IO.FileStream]::new($path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
                $probe.Dispose()
            } catch [System.IO.IOException] { throw [System.InvalidOperationException]::new('lock-held') }
            $existing = (Read-AwxAutoIntakeUtf8File -Path $path -MaxBytes 4096 | ConvertFrom-Json -ErrorAction Stop)
            if (-not (Test-AwxAutoIntakeExactPropertySet -Value $existing -Expected @('schemaVersion','pid','token')) -or [string]$existing.schemaVersion -cne 'awx.desktop-patchdrop-auto-intake.lock.v1' -or -not (Test-AwxAutoIntakeNonNegativeCount $existing.pid) -or [string]$existing.token -notmatch '^[a-f0-9]{32}$') { throw 'invalid' }
            try { Get-Process -Id ([int]$existing.pid) -ErrorAction Stop | Out-Null; throw [System.InvalidOperationException]::new('lock-held') }
            catch [System.InvalidOperationException] { throw }
            catch { throw [System.InvalidOperationException]::new('lock-owner-unproven') }
        } catch [System.InvalidOperationException] { throw }
        catch { throw [System.InvalidOperationException]::new('lock-corrupt') }
    }
    try {
        $content = ([ordered]@{ schemaVersion = 'awx.desktop-patchdrop-auto-intake.lock.v1'; pid = $PID; token = $token } | ConvertTo-Json -Compress)
        $bytes = [System.Text.UTF8Encoding]::new($false).GetBytes($content)
        $stream.Write($bytes, 0, $bytes.Length); $stream.Flush($true)
        $lock = [pscustomobject]@{ Path = $path; Stream = $stream; Token = $token }
        $lock | Add-Member -MemberType ScriptMethod -Name Dispose -Value {
            try { if ($null -ne $this.Stream) { $this.Stream.Dispose() } }
            finally {
                try {
                    $current = Read-AwxAutoIntakeUtf8File -Path $this.Path -MaxBytes 4096 | ConvertFrom-Json -ErrorAction Stop
                    if ([string]$current.token -ceq [string]$this.Token) { Remove-Item -LiteralPath $this.Path -Force -ErrorAction Stop }
                } catch { }
            }
        }
        return $lock
    } catch {
        $stream.Dispose()
        if (Test-Path -LiteralPath $path -PathType Leaf) { Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue }
        throw [System.InvalidOperationException]::new('lock-write-failed')
    }
}

function Test-AwxAutoIntakeLiveLock {
    param([object]$Lock, [string]$StateRoot)
    if ($null -eq $Lock -or $Lock.PSObject.Properties['Path'] -eq $null -or $Lock.PSObject.Properties['Stream'] -eq $null -or $Lock.PSObject.Properties['Token'] -eq $null -or $null -eq $Lock.Stream -or -not $Lock.Stream.CanWrite) { return $false }
    try {
        $root = Get-AwxAutoIntakeStateRoot $StateRoot
        $expected = Join-Path $root 'auto-intake.lock.json'
        if (-not ([string]$Lock.Path).Equals($expected, [System.StringComparison]::OrdinalIgnoreCase)) { return $false }
        if (-not (Test-Path -LiteralPath $expected -PathType Leaf)) { return $false }
        $Lock.Stream.Flush($true)
        $Lock.Stream.Position = 0
        $bytes = [byte[]]::new([int]$Lock.Stream.Length)
        [void]$Lock.Stream.Read($bytes, 0, $bytes.Length)
        $Lock.Stream.Position = $Lock.Stream.Length
        $current = [System.Text.UTF8Encoding]::new($false, $true).GetString($bytes) | ConvertFrom-Json -ErrorAction Stop
        return (Test-AwxAutoIntakeExactPropertySet -Value $current -Expected @('schemaVersion','pid','token')) -and [string]$current.schemaVersion -ceq 'awx.desktop-patchdrop-auto-intake.lock.v1' -and [string]$current.token -ceq [string]$Lock.Token
    } catch { return $false }
}

function Get-AwxAutoIntakeReplayHash {
    param([Parameter(Mandatory)][object]$Snapshot)
    if ($null -eq $Snapshot -or $null -eq $Snapshot.candidate) { throw [System.InvalidOperationException]::new('processed-state-invalid') }
    $bundle = [string]$Snapshot.candidate.bundleIdHash
    $patch = [string]$Snapshot.candidate.patchSha256
    $manifest = [string]$Snapshot.candidate.manifestSha256
    $root = [string]$Snapshot.candidate.rootPathSha256
    if ($bundle -notmatch '^[a-fA-F0-9]{64}$' -or $patch -notmatch '^[a-fA-F0-9]{64}$' -or $manifest -notmatch '^[a-fA-F0-9]{64}$' -or $root -notmatch '^[a-fA-F0-9]{64}$') { throw [System.InvalidOperationException]::new('processed-state-invalid') }
    return Get-AwxAutoIntakeSha256 ([System.Text.UTF8Encoding]::new($false).GetBytes($root.ToLowerInvariant() + ':' + $bundle.ToLowerInvariant() + ':' + $patch.ToLowerInvariant() + ':' + $manifest.ToLowerInvariant()))
}

function Read-AwxAutoIntakeProcessedState {
    param([Parameter(Mandatory)][string]$StateRoot)
    $root = Get-AwxAutoIntakeStateRoot $StateRoot
    $path = Join-Path $root 'processed-hashes.json'
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return [pscustomobject]@{ schemaVersion = $script:AwxAutoIntakeProcessedStateSchema; processedHashes = @() } }
    try {
        $state = Read-AwxAutoIntakeUtf8File -Path $path -MaxBytes $script:AwxAutoIntakeMaxStateBytes | ConvertFrom-Json -ErrorAction Stop
        $schemaProperties = @($state.PSObject.Properties | Where-Object { $_.Name -ceq 'schemaVersion' })
        if ($schemaProperties.Count -ne 1 -or $schemaProperties[0].Value -isnot [string] -or [string]$schemaProperties[0].Value -cne $script:AwxAutoIntakeProcessedStateSchema) {
            throw [System.InvalidOperationException]::new('processed-state-schema-unsupported')
        }
        if (-not (Test-AwxAutoIntakeExactPropertySet -Value $state -Expected @('schemaVersion','processedHashes')) -or $state.processedHashes -is [string] -or -not ($state.processedHashes -is [System.Collections.IEnumerable])) { throw 'invalid' }
        $hashes = @($state.processedHashes)
        if ($hashes.Count -gt $script:AwxAutoIntakeMaxLedgerRows) { throw 'invalid' }
        $seen = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
        foreach ($hash in $hashes) { if ($hash -isnot [string] -or $hash -cnotmatch '^[a-f0-9]{64}$' -or -not $seen.Add($hash)) { throw 'invalid' } }
        return [pscustomobject]@{ schemaVersion = $script:AwxAutoIntakeProcessedStateSchema; processedHashes = $hashes }
    } catch [System.InvalidOperationException] {
        if ($_.Exception.Message -ceq 'processed-state-schema-unsupported') { throw }
        throw [System.InvalidOperationException]::new('processed-state-corrupt')
    } catch {
        throw [System.InvalidOperationException]::new('processed-state-corrupt')
    }
}

function Test-AwxAutoIntakeProcessedHash {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$StateRoot, [Parameter(Mandatory)][object]$Snapshot)
    $key = Get-AwxAutoIntakeReplayHash $Snapshot
    return @((Read-AwxAutoIntakeProcessedState $StateRoot).processedHashes) -ccontains $key
}

function Get-AwxAutoIntakeReplayDecision {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$StateRoot, [Parameter(Mandatory)][object]$Snapshot)
    $candidate = $Snapshot.candidate
    try { $alreadyProcessed = Test-AwxAutoIntakeProcessedHash -StateRoot $StateRoot -Snapshot $Snapshot }
    catch [System.InvalidOperationException] {
        if ($_.Exception.Message -ceq 'processed-state-schema-unsupported') { return New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'processed-state-schema-unsupported' -PatchName ([string]$candidate.patchName) -BundleIdHash ([string]$candidate.bundleIdHash) }
        throw
    }
    if ($alreadyProcessed) {
        return New-AwxAutoIntakeDecision -Decision 'NOOP' -FailureClass 'already-processed' -PatchName ([string]$candidate.patchName) -BundleIdHash ([string]$candidate.bundleIdHash)
    }
    return New-AwxAutoIntakeDecision -Decision 'APPLY' -PatchName ([string]$candidate.patchName) -BundleIdHash ([string]$candidate.bundleIdHash)
}

function Register-AwxAutoIntakeProcessedHash {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$StateRoot,
        [Parameter(Mandatory)][object]$Snapshot,
        [object]$AdapterResult = $null,
        [Parameter(Mandatory)][bool]$FocusedVerificationPass,
        [Parameter(Mandatory)][bool]$RollbackReady,
        [object]$Lock = $null
    )
    $provenance = $null
    if ($null -eq $AdapterResult -or -not $script:AwxAutoIntakeAdapterProvenance.TryGetValue($AdapterResult, [ref]$provenance) -or [string]$AdapterResult.decision -cne 'APPLY' -or [int]$AdapterResult.exitCode -ne 0) { return New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'adapter-success-unproven' }
    if ($null -eq $Snapshot.candidate) { return New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'adapter-provenance-mismatch' }
    try { $snapshotReplay = Get-AwxAutoIntakeReplayHash $Snapshot } catch { return New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'adapter-provenance-mismatch' }
    foreach ($name in @('rootPathSha256','patchName','bundleIdHash','patchSha256','manifestSha256','replayHash')) {
        $expected = if ($name -ceq 'replayHash') { $snapshotReplay } else { [string]$Snapshot.candidate.$name }
        if ($provenance.PSObject.Properties[$name] -eq $null -or [string]$provenance.$name -cne [string]$expected) { return New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'adapter-provenance-mismatch' }
    }
    if (-not $FocusedVerificationPass) { return New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'focused-verification-unproven' }
    if (-not $RollbackReady) { return New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'rollback-unproven' }
    if (-not (Test-AwxAutoIntakeLiveLock -Lock $Lock -StateRoot $StateRoot)) { throw [System.InvalidOperationException]::new('lock-required') }
    $key = Get-AwxAutoIntakeReplayHash $Snapshot
    $root = Get-AwxAutoIntakeStateRoot $StateRoot
    try { $state = Read-AwxAutoIntakeProcessedState $root }
    catch [System.InvalidOperationException] {
        if ($_.Exception.Message -ceq 'processed-state-schema-unsupported') { return New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'processed-state-schema-unsupported' }
        throw
    }
    if (@($state.processedHashes) -ccontains $key) { return New-AwxAutoIntakeDecision -Decision 'NOOP' -FailureClass 'already-processed' }
    $hashes = @($state.processedHashes) + @($key)
    if ($hashes.Count -gt $script:AwxAutoIntakeMaxLedgerRows) { throw [System.InvalidOperationException]::new('processed-state-full') }
    Write-AwxAutoIntakeAtomicUtf8 -Directory $root -Name 'processed-hashes.json' -Content (([ordered]@{ schemaVersion = $script:AwxAutoIntakeProcessedStateSchema; processedHashes = $hashes } | ConvertTo-Json -Compress))
    return New-AwxAutoIntakeDecision -Decision 'APPLY'
}

function Test-AwxAutoIntakeBarePatchName {
    param([object]$PatchName)
    return ($PatchName -is [string]) -and ($PatchName -cmatch '^[A-Za-z0-9][A-Za-z0-9._-]*-v3\.patch$')
}

function Test-AwxAutoIntakePathContained {
    param([Parameter(Mandatory)][string]$Root, [Parameter(Mandatory)][string]$Path)
    try {
        $rootPath = [System.IO.Path]::GetFullPath($Root).TrimEnd('\\')
        $candidatePath = [System.IO.Path]::GetFullPath($Path)
        return $candidatePath.StartsWith($rootPath + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)
    } catch { return $false }
}

function Test-AwxAutoIntakeProductionRootEvidence {
    param([string]$Root, [object]$Evidence)
    if (-not (Test-AwxAutoIntakeExactPropertySet -Value $Evidence -Expected @('rootProven','rootPathSha256')) -or $Evidence.rootProven -isnot [bool] -or -not [bool]$Evidence.rootProven -or [string]$Evidence.rootPathSha256 -cnotmatch '^[a-f0-9]{64}$') { return $false }
    $normalized = [System.IO.Path]::GetFullPath($Root).TrimEnd('\\')
    $actual = Get-AwxAutoIntakeSha256 ([System.Text.UTF8Encoding]::new($false).GetBytes($normalized))
    return $actual -ceq [string]$Evidence.rootPathSha256
}

function Test-AwxAutoIntakeReparseFreePath {
    param([string]$Root, [string]$AdapterPath)
    try {
        $rootPath = [System.IO.Path]::GetFullPath($Root).TrimEnd('\\')
        $adapterFull = [System.IO.Path]::GetFullPath($AdapterPath)
        if (-not (Test-AwxAutoIntakePathContained -Root $rootPath -Path $adapterFull)) { return $false }
        if (((Get-Item -LiteralPath $rootPath -Force -ErrorAction Stop).Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) { return $false }
        $relative = $adapterFull.Substring($rootPath.Length).TrimStart('\\')
        $current = $rootPath
        foreach ($part in $relative.Split([char]'\')) {
            if ([string]::IsNullOrEmpty($part)) { continue }
            $current = Join-Path $current $part
            $item = Get-Item -LiteralPath $current -Force -ErrorAction Stop
            if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) { return $false }
        }
        return $true
    } catch { return $false }
}

function New-AwxAutoIntakeAdapterResult {
    param(
        [string]$Decision,
        [string]$FailureClass,
        [int]$ExitCode = -1,
        [object]$Stdout = $null,
        [object]$Stderr = $null,
        [bool]$ChildExited = $false,
        [bool]$JobAssignmentProven = $false,
        [bool]$JobResumed = $false,
        [bool]$HandleAllowlistProven = $false,
        [bool]$AttributeSizingProbeProven = $false,
        [bool]$SentinelInherited = $false,
        [bool]$RootTerminationAttempted = $false,
        [bool]$RootTerminationSucceeded = $false,
        [int]$RootProcessId = 0,
        [string]$RuntimeReason = ''
    )
    [pscustomobject]@{
        decision = $Decision
        failureClass = $FailureClass
        exitCode = $ExitCode
        stdoutHash = if ($null -eq $Stdout) { '' } else { [string]$Stdout.hash }
        stderrHash = if ($null -eq $Stderr) { '' } else { [string]$Stderr.hash }
        stdoutBytes = if ($null -eq $Stdout) { 0 } else { [int]$Stdout.byteCount }
        stderrBytes = if ($null -eq $Stderr) { 0 } else { [int]$Stderr.byteCount }
        childExited = $ChildExited
        jobAssignmentProven = $JobAssignmentProven
        jobResumed = $JobResumed
        handleAllowlistProven = $HandleAllowlistProven
        attributeSizingProbeProven = $AttributeSizingProbeProven
        sentinelInherited = $SentinelInherited
        rootTerminationAttempted = $RootTerminationAttempted
        rootTerminationSucceeded = $RootTerminationSucceeded
        rootProcessId = $RootProcessId
        runtimeReason = $RuntimeReason
    }
}

function Invoke-AwxJanitorAdapter {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Root,
        [Parameter(Mandatory)][string]$PatchName,
        [Parameter(Mandatory)][string]$AdapterPath,
        [Parameter(Mandatory)][object]$ExpectedSnapshot,
        [object]$ProductionRootEvidence = $null,
        [switch]$TestMode
    )
    if (-not (Test-AwxAutoIntakeBarePatchName $PatchName)) { return New-AwxAutoIntakeAdapterResult -Decision 'HOLD' -FailureClass 'janitor-adapter-invalid' }
    try {
        $rootPath = [System.IO.Path]::GetFullPath($Root).TrimEnd('\\')
        $adapterFull = [System.IO.Path]::GetFullPath($AdapterPath)
        if (-not (Test-Path -LiteralPath $rootPath -PathType Container) -or -not (Test-Path -LiteralPath $adapterFull -PathType Leaf)) { throw 'invalid' }
        if ($TestMode) {
            if (-not (Test-AwxAutoIntakePathContained -Root $rootPath -Path $adapterFull)) { throw 'invalid' }
        } else {
            $expected = Join-Path $rootPath '__patch_drop__\janitor_apply_one.ps1'
            if (-not $adapterFull.Equals([System.IO.Path]::GetFullPath($expected), [System.StringComparison]::OrdinalIgnoreCase) -or -not (Test-AwxAutoIntakeReparseFreePath -Root $rootPath -AdapterPath $adapterFull)) { throw 'invalid' }
            if (-not (Test-AwxAutoIntakeProductionRootEvidence -Root $rootPath -Evidence $ProductionRootEvidence)) { return New-AwxAutoIntakeAdapterResult -Decision 'HOLD' -FailureClass 'production-root-unproven' }
        }
    } catch {
        return New-AwxAutoIntakeAdapterResult -Decision 'HOLD' -FailureClass 'janitor-adapter-invalid'
    }
    $bundleGuard = $null
    try {
        try {
            $bundleGuard = Open-AwxAutoIntakeBundleSnapshotGuard -Root $rootPath -PatchName $PatchName -ExpectedSnapshot $ExpectedSnapshot -TestMode:$TestMode
            $identity = $bundleGuard.Identity
        } catch {
            return New-AwxAutoIntakeAdapterResult -Decision 'HOLD' -FailureClass 'bundle-snapshot-changed'
        }
        Initialize-AwxAutoIntakeJobRunner
        $powershellPath = if ($TestMode -and -not [string]::IsNullOrWhiteSpace([string]$script:AwxAutoIntakeContractOverridePowerShellPath)) { [string]$script:AwxAutoIntakeContractOverridePowerShellPath } else { (Get-Command powershell.exe -ErrorAction Stop).Source }
        $commandLine = '"{0}" -NoProfile -ExecutionPolicy Bypass -File "{1}" -PatchName "{2}"' -f $powershellPath, $adapterFull, $PatchName
        $run = [AwxAutoIntakeJobRunner]::Run(
            $powershellPath,
            $commandLine,
            $rootPath,
            [int]$script:AwxAutoIntakeAdapterTimeoutMilliseconds,
            [int]$script:AwxAutoIntakeMaxAdapterOutputBytes,
            [int]$script:AwxAutoIntakeAdapterCleanupMilliseconds,
            ([bool]$TestMode -and [bool]$script:AwxAutoIntakeContractInjectAssignmentFailure),
            ([bool]$TestMode -and [bool]$script:AwxAutoIntakeContractProbeHandleAllowlist)
        )
        $stdout = [pscustomobject]@{ hash = Get-AwxAutoIntakeSha256 -Bytes ([byte[]]$run.Stdout); byteCount = ([byte[]]$run.Stdout).Length; truncated = [bool]$run.OutputOverflow }
        $stderr = [pscustomobject]@{ hash = Get-AwxAutoIntakeSha256 -Bytes ([byte[]]$run.Stderr); byteCount = ([byte[]]$run.Stderr).Length; truncated = [bool]$run.OutputOverflow }
        $runtimeArgs = @{ ExitCode = [int]$run.ExitCode; Stdout = $stdout; Stderr = $stderr; ChildExited = [bool]$run.TreeExited; JobAssignmentProven = [bool]$run.AssignmentSucceeded; JobResumed = [bool]$run.Resumed; HandleAllowlistProven = [bool]$run.HandleAllowlistProven; AttributeSizingProbeProven = [bool]$run.AttributeSizingProbeProven; SentinelInherited = [bool]$run.SentinelInherited; RootTerminationAttempted = [bool]$run.RootTerminationAttempted; RootTerminationSucceeded = [bool]$run.RootTerminationSucceeded; RootProcessId = [int]$run.RootProcessId; RuntimeReason = [string]$run.Failure }
        if ([string]$run.Failure -ceq 'process-create-failed') { return New-AwxAutoIntakeAdapterResult -Decision 'HOLD' -FailureClass 'janitor-process-create-failed' @runtimeArgs }
        if (-not [bool]$run.HandleAllowlistProven -or [bool]$run.SentinelInherited) { return New-AwxAutoIntakeAdapterResult -Decision 'HOLD' -FailureClass 'janitor-handle-inheritance-unproven' @runtimeArgs }
        if (-not [bool]$run.AssignmentSucceeded) { return New-AwxAutoIntakeAdapterResult -Decision 'HOLD' -FailureClass 'janitor-job-assignment-unproven' @runtimeArgs }
        if (-not [bool]$run.Resumed) { return New-AwxAutoIntakeAdapterResult -Decision 'HOLD' -FailureClass 'janitor-job-start-failed' @runtimeArgs }
        if (-not [bool]$run.TreeExited) { return New-AwxAutoIntakeAdapterResult -Decision 'HOLD' -FailureClass 'janitor-tree-cleanup-unproven' @runtimeArgs }
        if ([bool]$run.OutputOverflow) { return New-AwxAutoIntakeAdapterResult -Decision 'HOLD' -FailureClass 'janitor-output-overflow' @runtimeArgs }
        if ([bool]$run.TimedOut) { return New-AwxAutoIntakeAdapterResult -Decision 'HOLD' -FailureClass 'janitor-timeout' @runtimeArgs }
        if (-not [string]::IsNullOrEmpty([string]$run.Failure)) { return New-AwxAutoIntakeAdapterResult -Decision 'HOLD' -FailureClass 'janitor-failed' @runtimeArgs }
        if ([int]$run.ExitCode -ne 0) { return New-AwxAutoIntakeAdapterResult -Decision 'HOLD' -FailureClass 'janitor-failed' @runtimeArgs }
        $result = New-AwxAutoIntakeAdapterResult -Decision 'APPLY' -FailureClass '' @runtimeArgs
        $script:AwxAutoIntakeAdapterProvenance.Add($result, $identity)
        return $result
    } catch {
        return New-AwxAutoIntakeAdapterResult -Decision 'HOLD' -FailureClass 'janitor-failed'
    } finally {
        if ($null -ne $bundleGuard) { $bundleGuard.Dispose() }
    }
}

function Test-AwxAutoIntakeLedgerOutcome {
    param([object]$Outcome)
    if (-not (Test-AwxAutoIntakeExactPropertySet -Value $Outcome -Expected $script:AwxAutoIntakeOutcomeFields)) { return $false }
    if ([string]$Outcome.schemaVersion -cne $script:AwxAutoIntakeOutcomeSchema) { return $false }
    if ([string]$Outcome.runIdHash -cnotmatch '^[a-f0-9]{64}$') { return $false }
    if ([string]$Outcome.bundleIdHash -ne '' -and [string]$Outcome.bundleIdHash -cnotmatch '^[a-f0-9]{64}$') { return $false }
    if ([string]$Outcome.mode -ne '' -and [string]$Outcome.mode -cnotin @('OBSERVE','APPLY')) { return $false }
    if ([string]$Outcome.decision -cnotin @('APPLY','HOLD','REJECT','NOOP','OBSERVE')) { return $false }
    if ([string]$Outcome.failureClass -ne '' -and [string]$Outcome.failureClass -cnotmatch '^[a-z0-9][a-z0-9.-]{0,127}$') { return $false }
    if ([string]$Outcome.patchName -ne '' -and -not (Test-AwxAutoIntakeBarePatchName $Outcome.patchName)) { return $false }
    foreach ($name in @('queueCount','secretPatternHitCount','elapsedMs')) { if (-not (Test-AwxAutoIntakeNonNegativeCount $Outcome.$name)) { return $false } }
    foreach ($name in @('leaseConflict','dirtyOverlap','preimageMatch','verificationPassed','rollbackReady')) { if ($Outcome.$name -isnot [bool]) { return $false } }
    if ([string]$Outcome.rollbackResult -cnotin @('READY','UNPROVEN','NOT_APPLICABLE','AMBIGUOUS')) { return $false }
    if ([string]$Outcome.desktopFinalProof -cne 'evidence_needed') { return $false }
    foreach ($property in $Outcome.PSObject.Properties) { if (-not (Test-AwxAutoIntakeSafeValue $property.Value)) { return $false } }
    return $true
}

function Test-AwxAutoIntakeLedgerDecision { param([object]$Decision) return Test-AwxAutoIntakeLedgerOutcome $Decision }

function Read-AwxAutoIntakeLedgerLines {
    param([Parameter(Mandatory)][string]$StateRoot)
    $root = Get-AwxAutoIntakeStateRoot $StateRoot
    $path = Join-Path $root 'auto-intake-ledger.jsonl'
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return @() }
    try {
        $text = Read-AwxAutoIntakeUtf8File -Path $path -MaxBytes $script:AwxAutoIntakeMaxLedgerBytes
        $lines = @($text -split "`r?`n" | Where-Object { $_.Length -gt 0 })
        if ($lines.Count -gt $script:AwxAutoIntakeMaxLedgerRows) { throw 'invalid' }
        foreach ($line in $lines) {
            if ([System.Text.UTF8Encoding]::new($false).GetByteCount($line) -gt $script:AwxAutoIntakeMaxLedgerRowBytes -or -not (Test-AwxAutoIntakeLedgerOutcome ($line | ConvertFrom-Json -ErrorAction Stop))) { throw 'invalid' }
        }
        return $lines
    } catch {
        throw [System.InvalidOperationException]::new('ledger-corrupt')
    }
}

function Write-AwxAutoIntakeLedger {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$StateRoot, [Parameter(Mandatory)][object]$Decision, [object]$Lock = $null)
    if (-not (Test-AwxAutoIntakeLedgerOutcome $Decision)) { throw [System.InvalidOperationException]::new('ledger-invalid') }
    if (-not (Test-AwxAutoIntakeLiveLock -Lock $Lock -StateRoot $StateRoot)) { throw [System.InvalidOperationException]::new('lock-required') }
    $root = Get-AwxAutoIntakeStateRoot $StateRoot
    $row = $Decision | ConvertTo-Json -Compress
    $rowBytes = [System.Text.UTF8Encoding]::new($false).GetBytes($row)
    if ($rowBytes.Length -gt $script:AwxAutoIntakeMaxLedgerRowBytes) { throw [System.InvalidOperationException]::new('ledger-row-too-large') }
    $lines = @(Read-AwxAutoIntakeLedgerLines $root)
    if ($lines.Count -ge $script:AwxAutoIntakeMaxLedgerRows) {
        Write-AwxAutoIntakeAtomicUtf8 -Directory $root -Name 'auto-intake-ledger.previous.jsonl' -Content (($lines -join "`n") + "`n")
        $lines = @()
    }
    $content = ((@($lines) + @($row)) -join "`n") + "`n"
    Write-AwxAutoIntakeAtomicUtf8 -Directory $root -Name 'auto-intake-ledger.jsonl' -Content $content
    return Get-AwxAutoIntakeSha256 $rowBytes
}

function Read-AwxAutoIntakeRunEvidence {
    param([Parameter(Mandatory)][string]$Path)
    try {
        $bytes = Read-AwxAutoIntakeBoundedFileBytes -Path $Path -MaxBytes $script:AwxAutoIntakeMaxPolicyBytes -FailureClass 'apply-evidence-invalid'
        $value = [System.Text.UTF8Encoding]::new($false, $true).GetString($bytes) | ConvertFrom-Json -ErrorAction Stop
        $expected = @('schemaVersion') + $script:AwxAutoIntakeApplyEvidenceFields
        if (-not (Test-AwxAutoIntakeExactPropertySet -Value $value -Expected $expected) -or [string]$value.schemaVersion -cne $script:AwxAutoIntakeRunEvidenceSchema) { throw 'invalid' }
        $copy = [ordered]@{}
        foreach ($name in $script:AwxAutoIntakeApplyEvidenceFields) { $copy[$name] = $value.$name }
        return [pscustomobject]$copy
    } catch { throw [System.InvalidOperationException]::new('apply-evidence-invalid') }
}

function Read-AwxAutoIntakeVerificationEvidence {
    param([Parameter(Mandatory)][string]$Path)
    try {
        $bytes = Read-AwxAutoIntakeBoundedFileBytes -Path $Path -MaxBytes 4096 -FailureClass 'focused-verification-unproven'
        $value = [System.Text.UTF8Encoding]::new($false, $true).GetString($bytes) | ConvertFrom-Json -ErrorAction Stop
        if (-not (Test-AwxAutoIntakeExactPropertySet -Value $value -Expected @('schemaVersion','verificationPassed','rollbackResult')) -or [string]$value.schemaVersion -cne $script:AwxAutoIntakeVerificationSchema -or $value.verificationPassed -isnot [bool] -or [string]$value.rollbackResult -cnotin @('READY','UNPROVEN','AMBIGUOUS')) { throw 'invalid' }
        return $value
    } catch { throw [System.InvalidOperationException]::new('focused-verification-unproven') }
}

function New-AwxAutoIntakeRunIdHash {
    $bytes = [System.Text.UTF8Encoding]::new($false).GetBytes([guid]::NewGuid().ToString('N'))
    return Get-AwxAutoIntakeSha256 $bytes
}

function New-AwxAutoIntakeOutcome {
    param(
        [Parameter(Mandatory)][string]$RunIdHash,
        [Parameter(Mandatory)][object]$Decision,
        [object]$Evidence = $null,
        [bool]$VerificationPassed = $false,
        [string]$RollbackResult = 'NOT_APPLICABLE',
        [int64]$ElapsedMs = 0
    )
    $secretPatternHitCount = 0
    $leaseConflict = $false
    $dirtyOverlap = $false
    $preimageMatch = $false
    $rollbackReady = $false
    if ($null -ne $Evidence) {
        if ($Evidence.PSObject.Properties['secretPatternHitCount'] -ne $null -and (Test-AwxAutoIntakeNonNegativeCount $Evidence.secretPatternHitCount)) { $secretPatternHitCount = [int64]$Evidence.secretPatternHitCount }
        if ($Evidence.PSObject.Properties['activeLeaseCount'] -ne $null -and (Test-AwxAutoIntakeNonNegativeCount $Evidence.activeLeaseCount)) { $leaseConflict = [int64]$Evidence.activeLeaseCount -gt 0 }
        if ($Evidence.PSObject.Properties['dirtyOverlapCount'] -ne $null -and (Test-AwxAutoIntakeNonNegativeCount $Evidence.dirtyOverlapCount)) { $dirtyOverlap = [int64]$Evidence.dirtyOverlapCount -gt 0 }
        if ($Evidence.PSObject.Properties['preimageMatch'] -ne $null -and $Evidence.preimageMatch -is [bool]) { $preimageMatch = [bool]$Evidence.preimageMatch }
        if ($Evidence.PSObject.Properties['rollbackReady'] -ne $null -and $Evidence.rollbackReady -is [bool]) { $rollbackReady = [bool]$Evidence.rollbackReady }
    }
    $failureClass = [string]$Decision.failureClass
    if ($failureClass -ne '' -and $failureClass -cnotmatch '^[a-z0-9][a-z0-9.-]{0,127}$') { $failureClass = 'unclassified-failure' }
    return [pscustomobject][ordered]@{
        schemaVersion = $script:AwxAutoIntakeOutcomeSchema
        runIdHash = $RunIdHash.ToLowerInvariant()
        bundleIdHash = ([string]$Decision.bundleIdHash).ToLowerInvariant()
        mode = [string]$Decision.mode
        decision = [string]$Decision.decision
        failureClass = $failureClass
        patchName = [string]$Decision.patchName
        queueCount = [int64]$Decision.queueCount
        secretPatternHitCount = $secretPatternHitCount
        leaseConflict = $leaseConflict
        dirtyOverlap = $dirtyOverlap
        preimageMatch = $preimageMatch
        verificationPassed = $VerificationPassed
        rollbackReady = $rollbackReady
        rollbackResult = $RollbackResult
        elapsedMs = [Math]::Max([int64]0, $ElapsedMs)
        desktopFinalProof = 'evidence_needed'
    }
}

function New-AwxAutoIntakeNativeResult {
    param([Parameter(Mandatory)][object]$Outcome, [string]$LedgerRowSha256 = '')
    return [pscustomobject][ordered]@{
        schemaVersion = 'awx.desktop-patchdrop-auto-intake.result.v1'
        runIdHash = [string]$Outcome.runIdHash
        bundleIdHash = [string]$Outcome.bundleIdHash
        mode = [string]$Outcome.mode
        decision = [string]$Outcome.decision
        failureClass = [string]$Outcome.failureClass
        verificationPassed = [bool]$Outcome.verificationPassed
        rollbackResult = [string]$Outcome.rollbackResult
        ledgerRowSha256 = $LedgerRowSha256
        desktopFinalProof = 'evidence_needed'
    }
}

function Get-AwxAutoIntakeNativeExitCode {
    param([object]$Result)
    switch -CaseSensitive ([string]$Result.decision) {
        'APPLY' { return 0 }
        'OBSERVE' { return 0 }
        'NOOP' { return 0 }
        'HOLD' { return 2 }
        'REJECT' { return 3 }
        default { return 4 }
    }
}

function Test-AwxAutoIntakeGuidTempRoot {
    param([Parameter(Mandatory)][string]$Root)
    try {
        $full = [System.IO.Path]::GetFullPath($Root).TrimEnd('\')
        $temp = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\')
        if (-not $full.StartsWith($temp + '\', [System.StringComparison]::OrdinalIgnoreCase)) { return $false }
        $relative = $full.Substring($temp.Length + 1)
        return @($relative.Split([char]'\') | Where-Object { $_ -cmatch '^awx-auto-intake-[a-f0-9]{32}$' }).Count -eq 1
    } catch { return $false }
}

function Test-AwxAutoIntakeTestInvocationPaths {
    param([string]$Root, [string[]]$Paths)
    if (-not (Test-AwxAutoIntakeGuidTempRoot $Root)) { return $false }
    foreach ($path in $Paths) {
        if ([string]::IsNullOrWhiteSpace($path) -or -not (Test-AwxAutoIntakePathContained -Root $Root -Path $path)) { return $false }
    }
    return $true
}

function Invoke-AwxAutoIntakeRun {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$PolicyPath,
        [Parameter(Mandatory)][string]$Root,
        [Parameter(Mandatory)][string]$StateRoot,
        [string]$EvidencePath = '',
        [string]$VerificationEvidencePath = '',
        [Parameter(Mandatory)][string]$AdapterPath,
        [int]$AdapterTimeoutMilliseconds = 0,
        [switch]$TestMode
    )
    $clock = [System.Diagnostics.Stopwatch]::StartNew()
    $runIdHash = New-AwxAutoIntakeRunIdHash
    $lock = $null
    $evidence = $null
    $snapshot = $null
    $decision = New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'run-unproven'
    $verificationPassed = $false
    $rollbackResult = 'NOT_APPLICABLE'
    $ledgerHash = ''
    try {
        $rootPath = [System.IO.Path]::GetFullPath($Root).TrimEnd('\')
        $statePath = [System.IO.Path]::GetFullPath($StateRoot).TrimEnd('\')
        $policyFull = [System.IO.Path]::GetFullPath($PolicyPath)
        $adapterFull = [System.IO.Path]::GetFullPath($AdapterPath)
        if ($TestMode) {
            $testPaths = @($policyFull, $statePath, $adapterFull)
            if (-not [string]::IsNullOrWhiteSpace($EvidencePath)) { $testPaths += [System.IO.Path]::GetFullPath($EvidencePath) }
            if (-not [string]::IsNullOrWhiteSpace($VerificationEvidencePath)) { $testPaths += [System.IO.Path]::GetFullPath($VerificationEvidencePath) }
            if (-not (Test-AwxAutoIntakeTestInvocationPaths -Root $rootPath -Paths $testPaths)) { throw [System.InvalidOperationException]::new('test-root-invalid') }
            if ($AdapterTimeoutMilliseconds -ne 0 -and ($AdapterTimeoutMilliseconds -lt 50 -or $AdapterTimeoutMilliseconds -gt $script:AwxAutoIntakeAdapterTimeoutMilliseconds)) { throw [System.InvalidOperationException]::new('adapter-timeout-invalid') }
        } elseif ($AdapterTimeoutMilliseconds -ne 0) { throw [System.InvalidOperationException]::new('adapter-timeout-invalid') }

        $lock = Enter-AwxAutoIntakeLock -StateRoot $statePath
        try { $policy = Read-AwxAutoIntakePolicy -Path $policyFull }
        catch { $decision = New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'policy-invalid'; throw [System.OperationCanceledException]::new('flow-stop') }
        $patchDrop = Join-Path $rootPath '__patch_drop__'
        $snapshot = Get-AwxTopLevelBundleSnapshot -Root $patchDrop
        $decision = Get-AwxAutoIntakeDecision -Policy $policy -Snapshot $snapshot -Evidence $null
        if ([string]$decision.decision -ceq 'APPLY' -or ([string]$policy.mode -ceq 'APPLY' -and [string]$decision.failureClass -ceq 'apply-preconditions-unproven')) {
            if (-not $TestMode) {
                $decision = New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'desktop-final-proof-required' -Mode 'APPLY' -PatchName ([string]$snapshot.candidate.patchName) -BundleIdHash ([string]$snapshot.candidate.bundleIdHash) -QueueCount ([int]$snapshot.queueCount)
            } elseif ([string]::IsNullOrWhiteSpace($EvidencePath)) {
                $decision = New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'apply-preconditions-unproven' -Mode 'APPLY' -PatchName ([string]$snapshot.candidate.patchName) -BundleIdHash ([string]$snapshot.candidate.bundleIdHash) -QueueCount ([int]$snapshot.queueCount)
            } else {
                try { $evidence = Read-AwxAutoIntakeRunEvidence -Path $EvidencePath }
                catch { $decision = New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'apply-evidence-invalid' -Mode 'APPLY' -PatchName ([string]$snapshot.candidate.patchName) -BundleIdHash ([string]$snapshot.candidate.bundleIdHash) -QueueCount ([int]$snapshot.queueCount); throw [System.OperationCanceledException]::new('flow-stop') }
                $decision = Get-AwxAutoIntakeDecision -Policy $policy -Snapshot $snapshot -Evidence $evidence
                if ([string]$decision.decision -ceq 'APPLY') {
                    $replay = Get-AwxAutoIntakeReplayDecision -StateRoot $statePath -Snapshot $snapshot
                    if ([string]$replay.decision -cne 'APPLY') {
                        $decision = New-AwxAutoIntakeDecision -Decision $replay.decision -FailureClass $replay.failureClass -Mode 'APPLY' -PatchName ([string]$snapshot.candidate.patchName) -BundleIdHash ([string]$snapshot.candidate.bundleIdHash) -QueueCount ([int]$snapshot.queueCount)
                    } elseif ([string]::IsNullOrWhiteSpace($VerificationEvidencePath) -or -not (Test-Path -LiteralPath $VerificationEvidencePath -PathType Leaf)) {
                        $decision = New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'focused-verification-unproven' -Mode 'APPLY' -PatchName ([string]$snapshot.candidate.patchName) -BundleIdHash ([string]$snapshot.candidate.bundleIdHash) -QueueCount ([int]$snapshot.queueCount)
                    } else {
                        $savedTimeout = $script:AwxAutoIntakeAdapterTimeoutMilliseconds
                        if ($AdapterTimeoutMilliseconds -gt 0) { $script:AwxAutoIntakeAdapterTimeoutMilliseconds = $AdapterTimeoutMilliseconds }
                        try { $adapter = Invoke-AwxJanitorAdapter -Root $patchDrop -PatchName ([string]$snapshot.candidate.patchName) -AdapterPath $adapterFull -ExpectedSnapshot $snapshot -TestMode }
                        finally { $script:AwxAutoIntakeAdapterTimeoutMilliseconds = $savedTimeout }
                        if ([string]$adapter.decision -cne 'APPLY') {
                            $decision = New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass ([string]$adapter.failureClass) -Mode 'APPLY' -PatchName ([string]$snapshot.candidate.patchName) -BundleIdHash ([string]$snapshot.candidate.bundleIdHash) -QueueCount ([int]$snapshot.queueCount)
                            $rollbackResult = if ([bool]$adapter.jobResumed) { 'AMBIGUOUS' } elseif ([bool]$evidence.rollbackReady) { 'READY' } else { 'UNPROVEN' }
                        } else {
                            try { $verification = Read-AwxAutoIntakeVerificationEvidence -Path $VerificationEvidencePath }
                            catch { $verification = [pscustomobject]@{ verificationPassed = $false; rollbackResult = 'AMBIGUOUS' } }
                            $verificationPassed = [bool]$verification.verificationPassed
                            $rollbackResult = [string]$verification.rollbackResult
                            $registration = Register-AwxAutoIntakeProcessedHash -StateRoot $statePath -Snapshot $snapshot -AdapterResult $adapter -FocusedVerificationPass:$verificationPassed -RollbackReady:($rollbackResult -ceq 'READY') -Lock $lock
                            $decision = New-AwxAutoIntakeDecision -Decision ([string]$registration.decision) -FailureClass ([string]$registration.failureClass) -Mode 'APPLY' -PatchName ([string]$snapshot.candidate.patchName) -BundleIdHash ([string]$snapshot.candidate.bundleIdHash) -QueueCount ([int]$snapshot.queueCount)
                            if ([string]$registration.decision -cne 'APPLY' -and $rollbackResult -ceq 'READY') { $rollbackResult = 'AMBIGUOUS' }
                        }
                    }
                }
            }
        }
    } catch [System.OperationCanceledException] {
    } catch [System.InvalidOperationException] {
        $failure = [string]$_.Exception.Message
        if ($failure -notmatch '^[a-z0-9][a-z0-9.-]{0,127}$') { $failure = 'run-unproven' }
        if ($failure -eq 'lock-held') { $decision = New-AwxAutoIntakeDecision -Decision 'NOOP' -FailureClass 'already-running' }
        else { $decision = New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass $failure }
    } catch {
        $decision = New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'run-unproven'
    } finally {
        $clock.Stop()
    }

    $outcome = New-AwxAutoIntakeOutcome -RunIdHash $runIdHash -Decision $decision -Evidence $evidence -VerificationPassed:$verificationPassed -RollbackResult $rollbackResult -ElapsedMs $clock.ElapsedMilliseconds
    if ($null -ne $lock) {
        try { $ledgerHash = Write-AwxAutoIntakeLedger -StateRoot $StateRoot -Decision $outcome -Lock $lock }
        catch {
            $decision = New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'ledger-write-failed' -Mode ([string]$decision.mode) -PatchName ([string]$decision.patchName) -BundleIdHash ([string]$decision.bundleIdHash) -QueueCount ([int]$decision.queueCount)
            $outcome = New-AwxAutoIntakeOutcome -RunIdHash $runIdHash -Decision $decision -Evidence $evidence -VerificationPassed:$false -RollbackResult $(if ($rollbackResult -ceq 'READY') { 'AMBIGUOUS' } else { $rollbackResult }) -ElapsedMs $clock.ElapsedMilliseconds
        } finally { $lock.Dispose() }
    }
    return New-AwxAutoIntakeNativeResult -Outcome $outcome -LedgerRowSha256 $ledgerHash
}

if ($MyInvocation.InvocationName -ne '.') {
    $resolvedRoot = if ([string]::IsNullOrWhiteSpace($Root)) { Split-Path -Parent $PSScriptRoot } else { $Root }
    $resolvedAdapter = if ([string]::IsNullOrWhiteSpace($AdapterPath)) { Join-Path $resolvedRoot '__patch_drop__\janitor_apply_one.ps1' } else { $AdapterPath }
    $nativeItems = @(Invoke-AwxAutoIntakeRun -PolicyPath $PolicyPath -Root $resolvedRoot -StateRoot $StateRoot -EvidencePath $EvidencePath -VerificationEvidencePath $VerificationEvidencePath -AdapterPath $resolvedAdapter -AdapterTimeoutMilliseconds $AdapterTimeoutMilliseconds -TestMode:$TestMode)
    if ($nativeItems.Count -ne 1) {
        $fallbackDecision = New-AwxAutoIntakeDecision -Decision 'HOLD' -FailureClass 'run-output-invalid'
        $fallbackOutcome = New-AwxAutoIntakeOutcome -RunIdHash (New-AwxAutoIntakeRunIdHash) -Decision $fallbackDecision
        $nativeResult = New-AwxAutoIntakeNativeResult -Outcome $fallbackOutcome
    } else { $nativeResult = $nativeItems[0] }
    [Console]::Out.WriteLine(($nativeResult | ConvertTo-Json -Compress -Depth 4))
    [Console]::Out.Flush()
    [Environment]::Exit((Get-AwxAutoIntakeNativeExitCode $nativeResult))
}
