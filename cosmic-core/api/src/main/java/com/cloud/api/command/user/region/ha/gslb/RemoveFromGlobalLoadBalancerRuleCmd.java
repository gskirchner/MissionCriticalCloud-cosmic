package com.cloud.api.command.user.region.ha.gslb;

import com.cloud.api.APICommand;
import com.cloud.api.ApiConstants;
import com.cloud.api.ApiErrorCode;
import com.cloud.api.BaseAsyncCmd;
import com.cloud.api.Parameter;
import com.cloud.api.ServerApiException;
import com.cloud.api.response.FirewallRuleResponse;
import com.cloud.api.response.GlobalLoadBalancerResponse;
import com.cloud.api.response.SuccessResponse;
import com.cloud.context.CallContext;
import com.cloud.event.EventTypes;
import com.cloud.region.ha.GlobalLoadBalancerRule;
import com.cloud.region.ha.GlobalLoadBalancingRulesService;
import com.cloud.user.Account;
import com.cloud.utils.StringUtils;
import com.cloud.utils.exception.InvalidParameterValueException;

import javax.inject.Inject;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@APICommand(name = "removeFromGlobalLoadBalancerRule",
        description = "Removes a load balancer rule association with global load balancer rule",
        responseObject = SuccessResponse.class,
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false)
public class RemoveFromGlobalLoadBalancerRuleCmd extends BaseAsyncCmd {
    public static final Logger s_logger = LoggerFactory.getLogger(RemoveFromGlobalLoadBalancerRuleCmd.class.getName());

    private static final String s_name = "removefromloadbalancerruleresponse";

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////
    @Inject
    public GlobalLoadBalancingRulesService _gslbService;
    @Parameter(name = ApiConstants.ID,
            type = CommandType.UUID,
            entityType = GlobalLoadBalancerResponse.class,
            required = true,
            description = "The ID of the load balancer rule")
    private Long id;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////
    @Parameter(name = ApiConstants.LOAD_BALANCER_RULE_LIST,
            type = CommandType.LIST,
            collectionType = CommandType.UUID,
            entityType = FirewallRuleResponse.class,
            required = true,
            description = "the list load balancer rules that will be assigned to gloabal load balancer rule")
    private List<Long> loadBalancerRulesIds;

    @Override
    public String getEventType() {
        return EventTypes.EVENT_REMOVE_FROM_GLOBAL_LOAD_BALANCER_RULE;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public String getEventDescription() {
        return "removing load balancer rules:" + StringUtils.join(getLoadBalancerRulesIds(), ",") + " from global load balancer: " + getGlobalLoadBalancerRuleId();
    }

    public List<Long> getLoadBalancerRulesIds() {
        return loadBalancerRulesIds;
    }

    public Long getGlobalLoadBalancerRuleId() {
        return id;
    }

    @Override
    public String getSyncObjType() {
        return BaseAsyncCmd.gslbSyncObject;
    }

    @Override
    public Long getSyncObjId() {
        final GlobalLoadBalancerRule gslb = _gslbService.findById(id);
        if (gslb == null) {
            throw new InvalidParameterValueException("Unable to find load balancer rule: " + id);
        }
        return gslb.getId();
    }

    @Override
    public void execute() {
        CallContext.current().setEventDetails(
                "Global Load balancer rule Id: " + getGlobalLoadBalancerRuleId() + " VmIds: " + StringUtils.join(getLoadBalancerRulesIds(), ","));
        final boolean result = _gslbService.removeFromGlobalLoadBalancerRule(this);
        if (result) {
            final SuccessResponse response = new SuccessResponse(getCommandName());
            this.setResponseObject(response);
        } else {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to remove load balancer rule from global load balancer rule");
        }
    }

    @Override
    public String getCommandName() {
        return s_name;
    }

    @Override
    public long getEntityOwnerId() {
        final GlobalLoadBalancerRule globalLoadBalancerRule = _entityMgr.findById(GlobalLoadBalancerRule.class, getGlobalLoadBalancerRuleId());
        if (globalLoadBalancerRule == null) {
            return Account.ACCOUNT_ID_SYSTEM; // bad id given, parent this command to SYSTEM so ERROR events are tracked
        }
        return globalLoadBalancerRule.getAccountId();
    }
}
