#include "Ihook.h"
#include "fixPCOpcode.h"

#define ALIGN_PC(pc)	(pc & 0xFFFFFFFC)

/**
 * �޸�ҳ���ԣ��ĳɿɶ���д��ִ��
 * @param   pAddress   ��Ҫ�޸�������ʼ��ַ
 * @param   size       ��Ҫ�޸�ҳ���Եĳ��ȣ�byteΪ��λ
 * @return  bool       �޸��Ƿ�ɹ�
 */
bool ChangePageProperty(void *pAddress, size_t size)
{
    bool bRet = false;
    
    if(pAddress == NULL)
    {
        LOGI("change page property error.");
        return bRet;
    }
    
    //���������ҳ����������ʼ��ַ
    unsigned long ulPageSize = sysconf(_SC_PAGESIZE); //�õ�ҳ�Ĵ�С
    int iProtect = PROT_READ | PROT_WRITE | PROT_EXEC;
    unsigned long ulNewPageStartAddress = (unsigned long)(pAddress) & ~(ulPageSize - 1); //pAddress & 0x1111 0000 0000 0000
    long lPageCount = (size / ulPageSize) + 1;
    
    long l = 0;
    while(l < lPageCount)
    {
        //����mprotect��ҳ����
        int iRet = mprotect((const void *)(ulNewPageStartAddress), ulPageSize, iProtect);
        if(-1 == iRet)
        {
            LOGI("mprotect error:%s", strerror(errno));
            return bRet;
        }
        l++; 
    }
    
    return true;
}

/**
 * ͨ��/proc/$pid/maps����ȡģ���ַ
 * @param   pid                 ģ�����ڽ���pid���������������̣�����С��0��ֵ����-1
 * @param   pszModuleName       ģ������
 * @return  void*               ģ���ַ�������򷵻�0
 */
void * GetModuleBaseAddr(pid_t pid, char* pszModuleName)
{
    FILE *pFileMaps = NULL;
    unsigned long ulBaseValue = 0;
    char szMapFilePath[256] = {0};
    char szFileLineBuffer[1024] = {0};
    LOGI("first fork(): I'am father pid=%d", getpid());

    LOGI("Pid is %d\n",pid);

    //pid�жϣ�ȷ��maps�ļ�
    if (pid < 0)
    {
        snprintf(szMapFilePath, sizeof(szMapFilePath), "/proc/self/maps");
    }
    else
    {
        snprintf(szMapFilePath, sizeof(szMapFilePath),  "/proc/%d/maps", pid);
    }

    pFileMaps = fopen(szMapFilePath, "r");
    if (NULL == pFileMaps)
    {
        return (void *)ulBaseValue;
    }
    LOGI("%d",pFileMaps);

    LOGI("Get map.\n");

    //ѭ������maps�ļ����ҵ���Ӧģ�飬��ȡ��ַ��Ϣ
    while (fgets(szFileLineBuffer, sizeof(szFileLineBuffer), pFileMaps) != NULL)
    {
        //LOGI("%s\n",szFileLineBuffer);
        //LOGI("%s\n",pszModuleName);
        if (strstr(szFileLineBuffer, pszModuleName))
        {
            LOGI("%s\n",szFileLineBuffer);
            char *pszModuleAddress = strtok(szFileLineBuffer, "-");
            if (pszModuleAddress)
            {
                ulBaseValue = strtoul(pszModuleAddress, NULL, 16);

                if (ulBaseValue == 0x8000)
                    ulBaseValue = 0;

                break;
            }
        }
    }
    fclose(pFileMaps);
    return (void *)ulBaseValue;
}

/**
 * arm��inline hook������Ϣ���ݣ�����ԭ�ȵ�opcodes��
 * @param  pstInlineHook inlinehook��Ϣ
 * @return               ��ʼ����Ϣ�Ƿ�ɹ�
 */
bool InitArmHookInfo(INLINE_HOOK_INFO* pstInlineHook)
{
    bool bRet = false;
    uint32_t *currentOpcode = pstInlineHook->pHookAddr;

    for(int i=0;i<BACKUP_CODE_NUM_MAX;i++){
        pstInlineHook->backUpFixLengthList[i] = -1;
    }
    LOGI("pstInlineHook->szbyBackupOpcodes is at %x",pstInlineHook->szbyBackupOpcodes);

    
    if(pstInlineHook == NULL)
    {
        LOGI("pstInlineHook is null");
        return bRet;
    }

    pstInlineHook->backUpLength = 24;
    
    memcpy(pstInlineHook->szbyBackupOpcodes, pstInlineHook->pHookAddr, pstInlineHook->backUpLength);

    for(int i=0;i<6;i++){
        //currentOpcode += i; //GToad BUG
        LOGI("Arm64 Opcode to fix %d : %x",i,*currentOpcode);
        LOGI("Fix length : %d",lengthFixArm32(*currentOpcode));
        pstInlineHook->backUpFixLengthList[i] = lengthFixArm64(*currentOpcode);
        currentOpcode += 1; //GToad BUG
    }
    
    return true;
}

/**
 * ����ihookstub.s�е�shellcode����׮����ת��pstInlineHook->onCallBack�����󣬻ص��Ϻ���
 * @param  pstInlineHook inlinehook��Ϣ
 * @return               inlinehook׮�Ƿ���ɹ�
 */
bool BuildStub(INLINE_HOOK_INFO* pstInlineHook)
{
    bool bRet = false;
    
    while(1)
    {
        if(pstInlineHook == NULL)
        {
            LOGI("pstInlineHook is null");
            break;
        }
        
        void *p_shellcode_start_s = &_shellcode_start_s;
        void *p_shellcode_end_s = &_shellcode_end_s;
        void *p_hookstub_function_addr_s = &_hookstub_function_addr_s;
        void *p_old_function_addr_s = &_old_function_addr_s;

        size_t sShellCodeLength = p_shellcode_end_s - p_shellcode_start_s;
        //mallocһ���µ�stub����
        void *pNewShellCode = malloc(sShellCodeLength);
        if(pNewShellCode == NULL)
        {
            LOGI("shell code malloc fail.");
            break;
        }
        memcpy(pNewShellCode, p_shellcode_start_s, sShellCodeLength);
        //����stub����ҳ���ԣ��ĳɿɶ���д��ִ��
        if(ChangePageProperty(pNewShellCode, sShellCodeLength) == false)
        {
            LOGI("change shell code page property fail.");
            break;
        }

        //������ת���ⲿstub����ȥ
        LOGI("_hookstub_function_addr_s : %lx",p_hookstub_function_addr_s);
        void **ppHookStubFunctionAddr = pNewShellCode + (p_hookstub_function_addr_s - p_shellcode_start_s);
        *ppHookStubFunctionAddr = pstInlineHook->onCallBack;
        LOGI("ppHookStubFunctionAddr : %lx",ppHookStubFunctionAddr);
        LOGI("*ppHookStubFunctionAddr : %lx",*ppHookStubFunctionAddr);
        
        //�����ⲿstub�������������ת�ĺ�����ַָ�룬��������Ϻ������µ�ַ
        pstInlineHook->ppOldFuncAddr  = pNewShellCode + (p_old_function_addr_s - p_shellcode_start_s);
            
        //���shellcode��ַ��hookinfo�У����ڹ���hook��λ�õ���תָ��
        pstInlineHook->pStubShellCodeAddr = pNewShellCode;

        

        bRet = true;
        break;
    }
    
    return bRet;
}


/**
 * ���첢���ARM��32����תָ���Ҫ�ⲿ��֤�ɶ���д����pCurAddress����8��bytes��С
 * @param  pCurAddress      ��ǰ��ַ��Ҫ������תָ���λ��
 * @param  pJumpAddress     Ŀ�ĵ�ַ��Ҫ�ӵ�ǰλ������ȥ�ĵ�ַ
 * @return                  ��תָ���Ƿ���ɹ�
 */
bool BuildArmJumpCode(void *pCurAddress , void *pJumpAddress)
{
    LOGI("LIVE4.3.1");
    bool bRet = false;
    while(1)
    {
        LOGI("LIVE4.3.2");
        if(pCurAddress == NULL || pJumpAddress == NULL)
        {
            LOGI("address null.");
            break;
        }    
        LOGI("LIVE4.3.3");    
        //LDR PC, [PC, #-4]
        //addr
        //LDR PC, [PC, #-4]��Ӧ�Ļ�����Ϊ��0xE51FF004
        //addrΪҪ��ת�ĵ�ַ������תָ�ΧΪ32λ������32λϵͳ��˵��Ϊȫ��ַ��ת��
        //���湹��õ���תָ�ARM��32λ������ָ��ֻ��Ҫ8��bytes��
        //BYTE szLdrPCOpcodes[8] = {0x04, 0xF0, 0x1F, 0xE5};

        //STP X1, X0, [SP, #-0x10]
        //LDR X0, 8
        //BR X0
        //ADDR(64)
        //LDR X0, [SP, -0x8]
        BYTE szLdrPCOpcodes[24] = {0xe1, 0x03, 0x3f, 0xa9, 0x40, 0x00, 0x00, 0x58, 0x00, 0x00, 0x1f, 0xd6};
        //��Ŀ�ĵ�ַ��������תָ���λ��
        memcpy(szLdrPCOpcodes + 12, &pJumpAddress, 8);
        szLdrPCOpcodes[20] = 0xE0;
        szLdrPCOpcodes[21] = 0x83;
        szLdrPCOpcodes[22] = 0x5F;
        szLdrPCOpcodes[23] = 0xF8;
        LOGI("LIVE4.3.4");
        
        //������õ���תָ��ˢ��ȥ
        memcpy(pCurAddress, szLdrPCOpcodes, 24);
        LOGI("LIVE4.3.5");
        //__flush_cache(*((uint32_t*)pCurAddress), 20);
        //__builtin___clear_cache (*((uint64_t*)pCurAddress), *((uint64_t*)(pCurAddress+20)));
        //cacheflush(*((uint32_t*)pCurAddress), 20, 0);
        LOGI("LIVE4.3.6");
        bRet = true;
        break;
    }
    LOGI("LIVE4.3.7");
    return bRet;
}


/**
 * ���챻inline hook�ĺ���ͷ����ԭԭ����ͷ+������ת
 * ���ǿ�����ת���ɣ�ͬʱ���stub shellcode�е�oldfunction��ַ��hookinfo�����old������ַ
 * ���ʵ��û��ָ���޸����ܣ�����HOOK��λ��ָ����漰PC����Ҫ�ض���ָ��
 * @param  pstInlineHook inlinehook��Ϣ
 * @return               ԭ���������Ƿ�ɹ�
 */
bool BuildOldFunction(INLINE_HOOK_INFO* pstInlineHook)
{
    bool bRet = false;

    void *fixOpcodes;
    int fixLength;
    LOGI("LIVE3.1");

    fixOpcodes = mmap(NULL, PAGE_SIZE, PROT_READ | PROT_WRITE | PROT_EXEC, MAP_ANONYMOUS | MAP_PRIVATE, 0, 0);
    LOGI("LIVE3.2");
    while(1)
    {
        if(pstInlineHook == NULL)
        {
            LOGI("pstInlineHook is null");
            break;
        }
        LOGI("LIVE3.3");
        
        //8��bytes���ԭ����opcodes������8��bytes�����ת��hook���������תָ��
        void * pNewEntryForOldFunction = malloc(200);
        if(pNewEntryForOldFunction == NULL)
        {
            LOGI("new entry for old function malloc fail.");
            break;
        }
        LOGI("LIVE3.4");

        pstInlineHook->pNewEntryForOldFunction = pNewEntryForOldFunction;
        LOGI("%x",pNewEntryForOldFunction);
        
        if(ChangePageProperty(pNewEntryForOldFunction, 200) == false)
        {
            LOGI("change new entry page property fail.");
            break;
        }
        LOGI("LIVE3.5");
        
        fixLength = fixPCOpcodeArm(fixOpcodes, pstInlineHook); //�ѵ������ֵ���ʼ��ַ����ȥ
        memcpy(pNewEntryForOldFunction, fixOpcodes, fixLength);
        LOGI("LIVE3.6");
        //memcpy(pNewEntryForOldFunction, pstInlineHook->szbyBackupOpcodes, 8);
        //�����תָ��
        if(BuildArmJumpCode(pNewEntryForOldFunction + fixLength, pstInlineHook->pHookAddr + pstInlineHook->backUpLength - 4) == false)
        {
            LOGI("build jump opcodes for new entry fail.");
            break;
        }
        LOGI("LIVE3.7");
        //���shellcode��stub�Ļص���ַ
        *(pstInlineHook->ppOldFuncAddr) = pNewEntryForOldFunction;
        LOGI("LIVE3.8");
        
        bRet = true;
        break;
    }
    LOGI("LIVE3.9");
    
    return bRet;
}


    
/**
 * ��ҪHOOK��λ�ã�������ת����ת��shellcode stub��
 * @param  pstInlineHook inlinehook��Ϣ
 * @return               ԭ����תָ���Ƿ���ɹ�
 */
bool RebuildHookTarget(INLINE_HOOK_INFO* pstInlineHook)
{
    bool bRet = false;
    
    while(1)
    {
        LOGI("LIVE4.1");
        if(pstInlineHook == NULL)
        {
            LOGI("pstInlineHook is null");
            break;
        }
        LOGI("LIVE4.2");
        //�޸�ԭλ�õ�ҳ���ԣ���֤��д
        if(ChangePageProperty(pstInlineHook->pHookAddr, 24) == false)
        {
            LOGI("change page property error.");
            break;
        }
        LOGI("LIVE4.3");
        //�����תָ��
        if(BuildArmJumpCode(pstInlineHook->pHookAddr, pstInlineHook->pStubShellCodeAddr) == false)
        {
            LOGI("build jump opcodes for new entry fail.");
            break;
        }
        LOGI("LIVE4.4");
        bRet = true;
        break;
    }
    LOGI("LIVE4.5");
    
    return bRet;
}


/**
 * ARM�µ�inlinehook
 * @param  pstInlineHook inlinehook��Ϣ
 * @return               inlinehook�Ƿ����óɹ�
 */
bool HookArm(INLINE_HOOK_INFO* pstInlineHook)
{
    bool bRet = false;
    LOGI("HookArm()");
    
    while(1)
    {
        //LOGI("pstInlineHook is null 1.");
        if(pstInlineHook == NULL)
        {
            LOGI("pstInlineHook is null.");
            break;
        }
        LOGI("LIVE1");

        //LOGI("Init Arm HookInfo fail 1.");
        //���㲽������ARM��inline hook�Ļ�����Ϣ
        if(InitArmHookInfo(pstInlineHook) == false)
        {
            LOGI("Init Arm HookInfo fail.");
            break;
        }
        LOGI("LIVE2");
        
        //LOGI("BuildStub fail 1.");
        //�ڶ���������stub�������Ǳ���Ĵ���״̬��ͬʱ��ת��Ŀ�꺯����Ȼ����ת��ԭ����
        //��ҪĿ���ַ������stub��ַ��ͬʱ����oldָ���������� 
        if(BuildStub(pstInlineHook) == false)
        {
            LOGI("BuildStub fail.");
            break;
        }
        LOGI("LIVE3");
        
        //LOGI("BuildOldFunction fail 1.");
        //���Ĳ��������ع�ԭ����ͷ���������޸�ָ�������ת�ص�ԭ��ַ��
        //��Ҫԭ������ַ
        
        if(BuildOldFunction(pstInlineHook) == false)
        {
            LOGI("BuildOldFunction fail.");
            break;
        }
        LOGI("LIVE4");
        
        //LOGI("RebuildHookAddress fail 1.");
        //��һ����������дԭ����ͷ��������ʵ��inline hook�����һ������д��ת
        //��Ҫcacheflush����ֹ����
        if(RebuildHookTarget(pstInlineHook) == false)
        {
            LOGI("RebuildHookAddress fail.");
            break;
        }
        LOGI("LIVE5");
        
        bRet = true;
        break;
    }
    LOGI("LIVE6");

    return bRet;
}


