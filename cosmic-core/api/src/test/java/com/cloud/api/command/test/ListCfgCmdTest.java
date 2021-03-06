package com.cloud.api.command.test;

import com.cloud.api.ResponseGenerator;
import com.cloud.api.command.admin.config.ListCfgsByCmd;
import com.cloud.api.response.ConfigurationResponse;
import com.cloud.api.response.ListResponse;
import com.cloud.config.Configuration;
import com.cloud.server.ManagementService;
import com.cloud.utils.Pair;

import java.util.ArrayList;
import java.util.List;

import junit.framework.Assert;
import junit.framework.TestCase;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class ListCfgCmdTest extends TestCase {

    private ListCfgsByCmd listCfgsByCmd;
    private ManagementService mgr;
    private ResponseGenerator responseGenerator;

    @Override
    @Before
    public void setUp() {
        responseGenerator = Mockito.mock(ResponseGenerator.class);
        mgr = Mockito.mock(ManagementService.class);
        listCfgsByCmd = new ListCfgsByCmd();
    }

    @Test
    public void testCreateSuccess() {

        final Configuration cfg = Mockito.mock(Configuration.class);
        listCfgsByCmd._mgr = mgr;
        listCfgsByCmd._responseGenerator = responseGenerator;

        final List<Configuration> configList = new ArrayList<>();
        configList.add(cfg);

        final Pair<List<? extends Configuration>, Integer> result = new Pair<>(configList, 1);

        try {
            Mockito.when(mgr.searchForConfigurations(listCfgsByCmd)).thenReturn(result);
        } catch (final Exception e) {
            Assert.fail("Received exception when success expected " + e.getMessage());
        }
        final ConfigurationResponse cfgResponse = new ConfigurationResponse();
        cfgResponse.setName("Test case");
        Mockito.when(responseGenerator.createConfigurationResponse(cfg)).thenReturn(cfgResponse);

        listCfgsByCmd.execute();
        Mockito.verify(responseGenerator).createConfigurationResponse(cfg);

        final ListResponse<ConfigurationResponse> actualResponse = (ListResponse<ConfigurationResponse>) listCfgsByCmd.getResponseObject();
        Assert.assertEquals(cfgResponse, actualResponse.getResponses().get(0));
    }
}
